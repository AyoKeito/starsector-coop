package coop.ui;

import com.fs.starfarer.api.campaign.VisualPanelAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The visual-panel side of a coop dialog, as proxies: {@code showCustomPanel} to
 * {@code createUIElement} to {@code addPara} to a {@link LabelAPI} whose {@code setText} calls are
 * recorded. Shared by the lobby and reconnect dialog tests, because both now put every ticking
 * number in a label rather than in the text panel, and "the label moved and nothing else did" is the
 * assertion that matters in both.
 */
final class RecordingLiveLine {

    /** Every string the label was ever given, the one from {@code addPara} included. */
    final List<String> texts = new ArrayList<>();
    /** Just the {@code setText} calls, which are the per-second updates. */
    final List<String> setTexts = new ArrayList<>();
    int panelsShown;
    /** Models an engine that will not give out a custom panel. */
    boolean throwOnShowCustomPanel;
    /** Models a label that refuses the update. */
    boolean throwOnSetText;

    String text() {
        return texts.isEmpty() ? "" : texts.get(texts.size() - 1);
    }

    VisualPanelAPI proxy() {
        return (VisualPanelAPI) Proxy.newProxyInstance(
                VisualPanelAPI.class.getClassLoader(),
                new Class<?>[]{VisualPanelAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "showCustomPanel" -> {
                        if (throwOnShowCustomPanel) {
                            throw new IllegalStateException("no custom panel for you");
                        }
                        panelsShown++;
                        yield customPanel();
                    }
                    case "toString" -> "RecordingVisualPanel";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private CustomPanelAPI customPanel() {
        return (CustomPanelAPI) Proxy.newProxyInstance(
                CustomPanelAPI.class.getClassLoader(),
                new Class<?>[]{CustomPanelAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createUIElement" -> element();
                    case "addUIElement" -> position();
                    case "toString" -> "RecordingCustomPanel";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private TooltipMakerAPI element() {
        return (TooltipMakerAPI) Proxy.newProxyInstance(
                TooltipMakerAPI.class.getClassLoader(),
                new Class<?>[]{TooltipMakerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "addPara" -> {
                        texts.add((String) args[0]);
                        yield label();
                    }
                    case "toString" -> "RecordingElement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private LabelAPI label() {
        return (LabelAPI) Proxy.newProxyInstance(
                LabelAPI.class.getClassLoader(),
                new Class<?>[]{LabelAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setText" -> {
                        if (throwOnSetText) {
                            throw new IllegalStateException("no setText for you");
                        }
                        texts.add((String) args[0]);
                        setTexts.add((String) args[0]);
                        yield null;
                    }
                    case "getText" -> text();
                    case "autoSizeToWidth" -> position();
                    case "toString" -> "RecordingLabel";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PositionAPI position() {
        return (PositionAPI) Proxy.newProxyInstance(
                PositionAPI.class.getClassLoader(),
                new Class<?>[]{PositionAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "RecordingPosition";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    // inTL, setSize and friends all return the position for chaining.
                    default -> proxy;
                });
    }
}
