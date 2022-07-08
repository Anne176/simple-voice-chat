package de.maxhenkel.voicechat.voice.client.speaker;

import com.mojang.math.Vector3f;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.VoicechatClient;
import de.maxhenkel.voicechat.api.events.OpenALSoundEvent;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.voice.client.SoundManager;
import de.maxhenkel.voicechat.voice.common.NamedThreadPoolFactory;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL11;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class ALSpeakerBase implements Speaker {

    protected final Minecraft mc;
    protected final SoundManager soundManager;
    protected final int sampleRate;
    protected int bufferSize;
    protected int bufferSampleSize;
    protected int source;
    protected volatile int bufferIndex;
    protected final int[] buffers;
    protected final ExecutorService executor;

    @Nullable
    protected UUID audioChannelId;

    public ALSpeakerBase(SoundManager soundManager, int sampleRate, int bufferSize, @Nullable UUID audioChannelId) {
        mc = Minecraft.getInstance();
        this.soundManager = soundManager;
        this.sampleRate = sampleRate;
        this.bufferSize = bufferSize;
        this.bufferSampleSize = bufferSize;
        this.audioChannelId = audioChannelId;
        this.buffers = new int[32];
        String threadName;
        if (audioChannelId == null) {
            threadName = "SoundSourceThread";
        } else {
            threadName = "SoundSourceThread-%s".formatted(audioChannelId);
        }
        executor = Executors.newSingleThreadExecutor(NamedThreadPoolFactory.create(threadName));
    }

    @Override
    public void open() throws SpeakerException {
        runInContext(this::openSync);
    }

    protected void openSync() {
        if (hasValidSourceSync()) {
            return;
        }
        source = AL11.alGenSources();
        SoundManager.checkAlError();
        AL11.alSourcei(source, AL11.AL_LOOPING, AL11.AL_FALSE);
        SoundManager.checkAlError();
        AL11.alDistanceModel(AL11.AL_NONE);
        SoundManager.checkAlError();
        AL11.alGenBuffers(buffers);
        SoundManager.checkAlError();
    }

    @Override
    public void play(short[] data, float volume, @Nullable Vec3 position) {
        runInContext(() -> {
            removeProcessedBuffersSync();
            int buffers = getQueuedBuffersSync();
            boolean stopped = getStateSync() == AL11.AL_INITIAL || getStateSync() == AL11.AL_STOPPED || buffers <= 1;
            if (stopped) {
                for (int i = 0; i < getBufferSize(); i++) {
                    writeSync(new short[bufferSize], 1F, position);
                }
            }

            writeSync(data, volume, position);

            if (stopped) {
                AL11.alSourcePlay(source);
                SoundManager.checkAlError();
            }
        });
    }

    protected int getBufferSize() {
        return VoicechatClient.CLIENT_CONFIG.outputBufferSize.get();
    }

    protected void writeSync(short[] data, float volume, @Nullable Vec3 position) {
        PluginManager.instance().onALSound(source, audioChannelId, position, OpenALSoundEvent.Pre.class);
        setPositionSync(position);
        PluginManager.instance().onALSound(source, audioChannelId, position, OpenALSoundEvent.class);

        AL11.alSourcef(source, AL11.AL_MAX_GAIN, 6F);
        SoundManager.checkAlError();
        AL11.alSourcef(source, AL11.AL_GAIN, volume);
        SoundManager.checkAlError();
        AL11.alListenerf(AL11.AL_GAIN, 1F);
        SoundManager.checkAlError();

        int queuedBuffers = getQueuedBuffersSync();
        if (queuedBuffers >= buffers.length) {
            Voicechat.LOGGER.warn("Full playback buffer: {}/{}", queuedBuffers, buffers.length);
            int sampleOffset = AL11.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
            SoundManager.checkAlError();
            int buffersToSkip = queuedBuffers - getBufferSize();
            AL11.alSourcei(source, AL11.AL_SAMPLE_OFFSET, sampleOffset + buffersToSkip * bufferSampleSize);
            SoundManager.checkAlError();
            removeProcessedBuffersSync();
        }

        AL11.alBufferData(buffers[bufferIndex], getFormat(), convert(data, position), sampleRate);
        SoundManager.checkAlError();
        AL11.alSourceQueueBuffers(source, buffers[bufferIndex]);
        SoundManager.checkAlError();
        bufferIndex = (bufferIndex + 1) % buffers.length;

        PluginManager.instance().onALSound(source, audioChannelId, position, OpenALSoundEvent.Post.class);
    }

    protected abstract int getFormat();

    protected short[] convert(short[] data, @Nullable Vec3 position) {
        return data;
    }

    protected void setPositionSync(@Nullable Vec3 soundPos) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 position = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        AL11.alListener3f(AL11.AL_POSITION, (float) position.x, (float) position.y, (float) position.z);
        SoundManager.checkAlError();
        AL11.alListenerfv(AL11.AL_ORIENTATION, new float[]{look.x(), look.y(), look.z(), up.x(), up.y(), up.z()});
        SoundManager.checkAlError();
        if (soundPos != null) {
            AL11.alSourcei(source, AL11.AL_SOURCE_RELATIVE, AL11.AL_FALSE);
            SoundManager.checkAlError();
            AL11.alSource3f(source, AL11.AL_POSITION, (float) soundPos.x, (float) soundPos.y, (float) soundPos.z);
            SoundManager.checkAlError();
        } else {
            AL11.alSourcei(source, AL11.AL_SOURCE_RELATIVE, AL11.AL_TRUE);
            SoundManager.checkAlError();
            AL11.alSource3f(source, AL11.AL_POSITION, 0F, 0F, 0F);
            SoundManager.checkAlError();
        }
    }

    @Override
    public void close() {
        runInContext(this::closeSync);
    }

    protected void closeSync() {
        if (hasValidSourceSync()) {
            if (getStateSync() == AL11.AL_PLAYING) {
                AL11.alSourceStop(source);
                SoundManager.checkAlError();
            }

            AL11.alDeleteSources(source);
            SoundManager.checkAlError();
            AL11.alDeleteBuffers(buffers);
            SoundManager.checkAlError();
        }
        source = 0;
        executor.shutdown();
    }

    public void checkBufferEmpty(Runnable onEmpty) {
        runInContext(() -> {
            if (getStateSync() == AL11.AL_STOPPED || getQueuedBuffersSync() <= 0) {
                onEmpty.run();
            }
        });
    }

    protected void removeProcessedBuffersSync() {
        int processed = AL11.alGetSourcei(source, AL11.AL_BUFFERS_PROCESSED);
        SoundManager.checkAlError();
        for (int i = 0; i < processed; i++) {
            AL11.alSourceUnqueueBuffers(source);
            SoundManager.checkAlError();
        }
    }

    protected int getStateSync() {
        int state = AL11.alGetSourcei(source, AL11.AL_SOURCE_STATE);
        SoundManager.checkAlError();
        return state;
    }

    protected int getQueuedBuffersSync() {
        int buffers = AL11.alGetSourcei(source, AL11.AL_BUFFERS_QUEUED);
        SoundManager.checkAlError();
        return buffers;
    }

    protected boolean hasValidSourceSync() {
        boolean validSource = AL11.alIsSource(source);
        SoundManager.checkAlError();
        return validSource;
    }

    public void runInContext(Runnable runnable) {
        soundManager.runInContext(executor, runnable);
    }

}
