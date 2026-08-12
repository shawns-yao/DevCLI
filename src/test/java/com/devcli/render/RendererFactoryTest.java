package com.devcli.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RendererFactoryTest {

    private String savedProp;

    @BeforeEach
    void saveProp() {
        savedProp = System.getProperty("devcli.renderer");
    }

    @AfterEach
    void restoreProp() {
        if (savedProp == null) {
            System.clearProperty("devcli.renderer");
        } else {
            System.setProperty("devcli.renderer", savedProp);
        }
    }

    @Test
    void defaultsToInlineWhenUnset() {
        System.clearProperty("devcli.renderer");
        // We can't easily clear env vars in tests; only verify property path
        assertEquals(RendererFactory.Mode.INLINE, RendererFactory.resolveMode());
    }

    @Test
    void removedLanternaValueFallsBackToInline() {
        System.setProperty("devcli.renderer", "lanterna");
        assertEquals(RendererFactory.Mode.INLINE, RendererFactory.resolveMode());
    }

    @Test
    void propertyValuePlainResolves() {
        System.setProperty("devcli.renderer", "plain");
        assertEquals(RendererFactory.Mode.PLAIN, RendererFactory.resolveMode());
    }

    @Test
    void propertyValueIsCaseInsensitive() {
        System.setProperty("devcli.renderer", "PLAIN");
        assertEquals(RendererFactory.Mode.PLAIN, RendererFactory.resolveMode());
    }

    @Test
    void unknownValueFallsBackToInline() {
        System.setProperty("devcli.renderer", "weird");
        assertEquals(RendererFactory.Mode.INLINE, RendererFactory.resolveMode());
    }

    @Test
    void removedTuiAliasFallsBackToInline() {
        System.setProperty("devcli.renderer", "tui");
        assertEquals(RendererFactory.Mode.INLINE, RendererFactory.resolveMode());
    }

    @Test
    void createPlainReturnsPlainRenderer() {
        Renderer renderer = RendererFactory.create(RendererFactory.Mode.PLAIN, null);
        assertInstanceOf(PlainRenderer.class, renderer);
    }

    @Test
    void createInlineReturnsRendererInstance() {
        Renderer renderer = RendererFactory.create(RendererFactory.Mode.INLINE, null);
        assertInstanceOf(PlainRenderer.class, renderer);
    }
}
