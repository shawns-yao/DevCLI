package com.devcli.render.inline;

import com.devcli.render.state.RunSnapshot;

/** live activity 区的 RunSnapshot 适配器。 */
final class ActivityView {
    private final InlineActivityDisplay display;

    ActivityView(InlineActivityDisplay display) {
        this.display = display;
    }

    void render(RunSnapshot snapshot) {
        if (display != null) display.updateSnapshot(snapshot);
    }
}
