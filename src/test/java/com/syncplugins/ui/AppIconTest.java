package com.syncplugins.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppIconTest {
    @Test
    void rendersBrandIconAtTrayAndWindowSizes() {
        assertThat(AppIcon.image(16).getWidth()).isEqualTo(16);
        assertThat(AppIcon.image(32).getHeight()).isEqualTo(32);
        assertThat(AppIcon.image(64).getWidth()).isEqualTo(64);
    }
}
