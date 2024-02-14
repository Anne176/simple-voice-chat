package de.maxhenkel.voicechat.gui.onboarding;

import com.mojang.blaze3d.vertex.PoseStack;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

public class SkipOnboardingScreen extends OnboardingScreenBase {

    private static final Component TITLE = Component.translatable("message.voicechat.onboarding.skip.title", CommonCompatibilityManager.INSTANCE.getModName()).withStyle(ChatFormatting.BOLD);
    private static final Component DESCRIPTION = Component.translatable("message.voicechat.onboarding.skip.description");
    private static final Component CONFIRM = Component.translatable("message.voicechat.onboarding.confirm");

    public SkipOnboardingScreen(@Nullable Screen previous) {
        super(TITLE, previous);
    }

    @Override
    protected void init() {
        super.init();

        addBackOrCancelButton();
        addPositiveButton(CONFIRM, button -> OnboardingManager.finishOnboarding());
    }

    @Override
    public Screen getNextScreen() {
        return previous;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        super.render(poseStack, mouseX, mouseY, partialTicks);
        renderTitle(poseStack, TITLE);
        renderMultilineText(poseStack, DESCRIPTION);
    }

}
