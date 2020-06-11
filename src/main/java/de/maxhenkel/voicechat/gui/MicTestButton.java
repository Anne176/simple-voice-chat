package de.maxhenkel.voicechat.gui;

import de.maxhenkel.voicechat.Config;
import de.maxhenkel.voicechat.Main;
import de.maxhenkel.voicechat.voice.client.AudioChannelConfig;
import de.maxhenkel.voicechat.voice.client.Client;
import de.maxhenkel.voicechat.voice.client.MicThread;
import de.maxhenkel.voicechat.voice.common.Utils;
import net.minecraft.client.gui.widget.button.AbstractButton;
import net.minecraft.util.text.TranslationTextComponent;

import javax.sound.sampled.*;

public class MicTestButton extends AbstractButton {

    private boolean micActive;
    private VoiceThread voiceThread;

    public MicTestButton(int xIn, int yIn, int widthIn, int heightIn) {
        super(xIn, yIn, widthIn, heightIn, null);
        if (getMic() == null) {
            micActive = false;
        }
        updateText();
    }

    private void updateText() {
        if (!active) {
            setMessage(new TranslationTextComponent("message.mic_test_unavailable").getFormattedText());
            return;
        }
        if (micActive) {
            setMessage(new TranslationTextComponent("message.mic_test_on").getFormattedText());
        } else {
            setMessage(new TranslationTextComponent("message.mic_test_off").getFormattedText());
        }
    }

    @Override
    public void render(int x, int y, float partialTicks) {
        super.render(x, y, partialTicks);
        if (voiceThread != null) {
            voiceThread.updateLastRender();
        }
    }

    public void setMicActive(boolean micActive) {
        this.micActive = micActive;
        updateText();
    }

    @Override
    public void onPress() {
        setMicActive(!micActive);
        updateText();
        if (micActive) {
            if (voiceThread != null) {
                voiceThread.close();
            }
            try {
                voiceThread = new VoiceThread();
                voiceThread.start();
            } catch (LineUnavailableException e) {
                setMicActive(false);
                e.printStackTrace();
            }
        } else {
            if (voiceThread != null) {
                voiceThread.close();
            }
        }
    }

    private TargetDataLine getMic() {
        Client client = Main.CLIENT_VOICE_EVENTS.getClient();
        if (client == null) {
            return null;
        }
        MicThread micThread = client.getMicThread();
        if (micThread == null) {
            return null;
        }
        return micThread.getMic();
    }

    private class VoiceThread extends Thread {

        private final AudioFormat audioFormat;
        private final TargetDataLine mic;
        private final SourceDataLine speaker;
        private final FloatControl gainControl;
        private boolean running;
        private long lastRender;

        public VoiceThread() throws LineUnavailableException {
            this.running = true;
            setDaemon(true);
            audioFormat = AudioChannelConfig.getMonoFormat();
            mic = getMic();
            if (mic == null) {
                throw new LineUnavailableException("No microphone");
            }
            DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, audioFormat);
            speaker = (SourceDataLine) AudioSystem.getLine(sourceInfo);
            speaker.open(audioFormat);
            speaker.start();

            gainControl = (FloatControl) speaker.getControl(FloatControl.Type.MASTER_GAIN);

            updateLastRender();
        }

        @Override
        public void run() {
            while (running) {
                if (System.currentTimeMillis() - lastRender > 500L) {
                    close();
                    return;
                }
                mic.start();
                int dataLength = AudioChannelConfig.getDataLength();
                if (mic.available() < dataLength) {
                    Utils.sleep(10);
                    continue;
                }
                byte[] buff = new byte[dataLength];
                while (mic.available() >= dataLength) {
                    mic.read(buff, 0, buff.length);
                }
                Utils.adjustVolumeMono(buff, Config.CLIENT.MICROPHONE_AMPLIFICATION.get().floatValue());

                gainControl.setValue(Math.min(Math.max(Utils.percentageToDB(Config.CLIENT.VOICE_CHAT_VOLUME.get().floatValue()), gainControl.getMinimum()), gainControl.getMaximum()));

                speaker.write(buff, 0, buff.length);
            }
        }

        public void updateLastRender() {
            lastRender = System.currentTimeMillis();
        }

        public void close() {
            Main.LOGGER.debug("Closing mic test audio channel");
            running = false;
            speaker.stop();
            speaker.flush();
            speaker.close();
            mic.stop();
            mic.flush();
        }

    }
}
