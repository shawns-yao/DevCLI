package com.devcli.render.inline;

import com.devcli.render.StatusInfo;
import com.devcli.render.state.RunSnapshot;

/** bottom dock 的 RunSnapshot 适配器。 */
final class StatusDock {
    private final BottomStatusBar bar;

    StatusDock(BottomStatusBar bar) {
        this.bar = bar;
    }

    void render(RunSnapshot snapshot) {
        if (bar != null) bar.updateSnapshot(snapshot);
    }

    void updateEnvironment(StatusInfo status) {
        if (bar != null) bar.update(status);
    }
}
