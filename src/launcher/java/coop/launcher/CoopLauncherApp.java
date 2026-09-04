package coop.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.log4j.Logger;

import coop.config.CoopOptionsRegistry;
import coop.launcher.CoopTheme.Card;
import coop.launcher.CoopTheme.Chip;
import coop.launcher.CoopTheme.Dot;
import coop.launcher.CoopTheme.Form;
import coop.net.CoopConnectionDoctor;
import coop.net.CoopPortMapper;

/**
 * Phase 31: the co-op launcher window.
 *
 * <p>It invents no configuration of its own. Every field is a key that already resolves through
 * {@code CoopOptionsStore}, and pressing Launch writes them to
 * {@code saves/common/coop_options.json.data} and starts {@code starsector.exe}. The two things the
 * mod cannot reach - the {@code coop-forks.jar} classpath entry in {@code vmparams} and the mod tick
 * in {@code enabled_mods.json} - are reported with the exact fix and never edited.
 *
 * <p>Engine-free by construction: this source set is compiled without {@code starfarer.api.jar} on
 * the classpath. The mod classes it does reuse ({@link CoopPortMapper},
 * {@link CoopConnectionDoctor}, {@link CoopOptionsRegistry}) do not link to the game API.
 *
 * <p>Layout (redesigned 2026-09-03): a header with the role switch, three cards (Session,
 * Connection, Install), an Advanced card hidden by default, a footer with the one primary button,
 * and a log drawer that opens when something worth reading lands in it.
 */
public final class CoopLauncherApp {

    private static final Logger LOG = Logger.getLogger(CoopLauncherApp.class);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Star ages, spelled out because {@code StarAge} lives in {@code starfarer.api.jar} and the
     * launcher is compiled without it. The mod validates the value again
     * ({@code CoopNewGameChoices.parseStarAge}) and warns rather than crashing if this list ever
     * drifts from the engine's.
     */
    private static final List<String> STAR_AGES = List.of("young", "average", "old", "mixed");

    private static final List<String> SECTOR_SIZES = List.of("small", "normal");
    private static final String DEFAULT_SECTOR_SIZE = "normal";
    private static final String DEFAULT_STAR_AGE = "mixed";

    private static final int DEFAULT_PORT = 7777;
    private static final long CHECK_TIMEOUT_MILLIS = 20_000L;
    private static final int CHECK_TICK_MILLIS = 50;

    private final ExecutorService background =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "coop-launcher-worker");
                thread.setDaemon(true);
                return thread;
            });

    private CoopInstallLayout layout;
    private CoopLauncherConfig config;
    private String launcherVersion = "dev";

    private JFrame frame;

    // header
    private JToggleButton hostSegment;
    private JToggleButton guestSegment;
    private Chip updateChip;

    // session card
    private Card sessionCard;
    private JLabel sessionHint;
    private JPanel sessionBody;
    private JPanel hostForm;
    private JPanel guestForm;
    private JTextField hostPortField;
    private JPasswordField hostPasswordField;
    private JTextField hostSeedField;
    private JTextField publicAddressField;
    private JTextField invitePreviewField;
    private JButton copyInviteButton;
    private JComboBox<String> sectorSizeBox;
    private JComboBox<String> sectorAgeBox;

    private JTextField guestInviteField;
    private JTextArea guestInviteNote;
    private JTextField guestHostField;
    private JTextField guestPortField;
    private JPasswordField guestPasswordField;
    private JTextField guestSeedField;
    private JTextField guestSectorSizeField;
    private JTextField guestSectorAgeField;

    // connection card
    private JButton connectionButton;
    private JPanel chipRow;
    private JTextArea connectionNote;

    // advanced card
    private Card advancedCard;
    private JComboBox<String> portMappingBox;
    private JSpinner reconnectGraceSpinner;
    private JComboBox<String> hudCornerBox;
    private JCheckBox diagnosticsBox;
    private JCheckBox wiretapBox;
    private JSpinner wiretapSampleSpinner;
    private JCheckBox frameProfileBox;
    private JSpinner bridgePortSpinner;
    private JSpinner interactionDelaySpinner;
    private JCheckBox fullFidelityBox;
    private JCheckBox ffDisableBox;
    private JCheckBox clockDisableBox;
    private JCheckBox allowGameVersionMismatchBox;
    private JCheckBox adoptCampaignBox;

    // install card
    private Chip installSummary;
    private JPanel rowsPanel;
    private JButton showAllButton;
    private boolean showAllRows;

    // footer + drawer
    private JButton launchButton;
    private JLabel footerHint;
    private boolean gameRunning;
    /**
     * Bumped by anything that takes the port away from a connection check in flight: a newer check,
     * a role switch, LAUNCH, the window closing. The result of an older check must not open the
     * launcher's listener afterwards.
     */
    private int checkGeneration;
    private JButton advancedToggle;
    private JButton logToggle;
    private JPanel drawer;
    private JTextArea statusArea;
    private JCheckBox includeSaveBox;
    private JButton bugReportButton;

    private List<CoopInstallCheck.Row> installRows = List.of();
    /** The update-check row, kept apart because it arrives on its own schedule and off the disk. */
    private CoopInstallCheck.Row updateRow;
    private String updateUrl = "";
    /**
     * Set once the player empties the host password field themselves. From then on nothing refills
     * it: a host who deliberately wants an open session should not have to fight the launcher about
     * it every time they touch the role switch.
     */
    private boolean hostPasswordCleared;
    /** True while the code, not the player, is writing the host password field. */
    private boolean writingHostPassword;
    /** True while the code, not the player, is writing the guest invite field. */
    private boolean writingGuestInvite;

    private CoopLauncherProbe.HostListener listener;
    private CoopLogTail logTail;
    private Process gameProcess;
    private javax.swing.Timer checkTimer;

    public static void main(String[] args) {
        CoopInstallLayout discovered = CoopInstallLayout.discover();
        File logFile = discovered == null
                ? new File("coop-launcher.log")
                : discovered.launcherLog();
        CoopLauncherLogging.configure(logFile);
        LOG.info("Coop launcher starting; layout " + (discovered == null ? "not found" : discovered));
        SwingUtilities.invokeLater(() -> new CoopLauncherApp().start(discovered));
    }

    private void start(CoopInstallLayout discovered) {
        CoopTheme.install();
        this.layout = discovered;
        this.launcherVersion = CoopInstallCheck.launcherVersion();
        buildFrame();
        if (layout == null) {
            append("This folder does not look like a Starsector install, so the launcher could not"
                    + " work out where the game is. Use \"Folder\" in the Install card to point at it.");
            setDrawerVisible(true);
            chooseInstallFolder();
        } else {
            adoptLayout(layout);
        }
        if (layout == null) {
            // Nothing was adopted, so prefill() - the only thing that picks a role - never ran.
            // Without this neither segment is selected: the window shows the host form while the
            // Connection button runs the guest path, and LAUNCH stays enabled with no reason in the
            // footer.
            hostSegment.setSelected(true);
            guestSegment.setSelected(false);
            onRoleChanged();
        }
        startUpdateCheck();
        if (hostSegment.isSelected() && publicAddressField.getText().trim().isEmpty()
                && System.getenv("COOP_LAUNCHER_PREVIEW") == null) {
            lookUpPublicAddress(null, true);
        }
        applyPreview();
        frame.setVisible(true);
    }

    /**
     * Dev only: {@code COOP_LAUNCHER_PREVIEW=host|guest} stages the window for a screenshot (role
     * selected, sample connection chips, log drawer open). Nothing is written and no socket opens.
     */
    private void applyPreview() {
        String preview = System.getenv("COOP_LAUNCHER_PREVIEW");
        if (preview == null || preview.isBlank()) {
            return;
        }
        LOG.info("Preview mode " + preview);
        boolean guest = preview.trim().equalsIgnoreCase("guest");
        boolean install = preview.trim().equalsIgnoreCase("install");
        hostSegment.setSelected(!guest);
        guestSegment.setSelected(guest);
        onRoleChanged();
        if (guest) {
            writingGuestInvite = true;
            guestInviteField.setText("coop://203.0.113.9:7777/?seed=MN-8402913377120455081&pw=k7mxq2rp4d&size=normal&age=mixed");
            writingGuestInvite = false;
            applyInviteText(guestInviteField.getText(), false);
            setChips(List.of(new Chip("TCP", CoopTheme.OK), new Chip("launcher 0.1.0", CoopTheme.OK),
                    new Chip("UDP", CoopTheme.OK), new Chip("3 ms", CoopTheme.OK)));
            note("The host's launcher answered on TCP and UDP. Press Launch when your host does.");
        } else {
            hostSeedField.setText("MN-8402913377120455081");
            publicAddressField.setText("203.0.113.9");
            setChips(List.of(new Chip("UPnP mapped", CoopTheme.OK), new Chip("203.0.113.9:7777", CoopTheme.OK),
                    new Chip("listening on 7777", CoopTheme.OK)));
            note("Your router opened 203.0.113.9:7777. Copy the invite and ask your partner to press"
                    + " Test connection. The full doctor block is in the log.");
        }
        if (install) {
            advancedCard.setVisible(true);
            showAllRows = true;
            renderRows();
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            frame.setSize(new Dimension(700, screen.height - 80));
            frame.setLocationRelativeTo(null);
            return;
        }
        setDrawerVisible(true);
    }

    // ---- window ---------------------------------------------------------------------------------

    private void buildFrame() {
        frame = new JFrame("Starsector Coop");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                shutdown();
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(CoopTheme.BG);
        root.add(buildHeader(), BorderLayout.NORTH);

        JPanel cards = new JPanel();
        cards.setOpaque(false);
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        cards.setBorder(BorderFactory.createEmptyBorder(4, 20, 8, 20));
        sessionCard = buildSessionCard();
        Card connectionCard = buildConnectionCard();
        advancedCard = buildAdvancedCard();
        Card installCard = buildInstallCard();
        for (Card card : List.of(sessionCard, connectionCard, advancedCard, installCard)) {
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            cards.add(card);
            cards.add(CoopTheme.vgap(14));
        }
        advancedCard.setVisible(false);

        CoopTheme.ScrollColumn cardsHolder = new CoopTheme.ScrollColumn();
        cardsHolder.add(cards, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(cardsHolder,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        root.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(buildFooter(), BorderLayout.NORTH);
        drawer = buildDrawer();
        drawer.setVisible(false);
        south.add(drawer, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(640, 600));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(new Dimension(700, Math.max(600, Math.min(860, screen.height - 80))));
        frame.setLocationRelativeTo(null);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new GridBagLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(18, 24, 14, 24));

        JLabel title = new JLabel("Starsector Coop");
        title.setFont(title.getFont().deriveFont(Font.BOLD, (float) title.getFont().getSize() + 9f));
        title.setForeground(CoopTheme.TEXT);
        JLabel version = CoopTheme.small("launcher " + launcherVersion);
        updateChip = new Chip("", CoopTheme.WARN);
        updateChip.setVisible(false);
        updateChip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        updateChip.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (!updateUrl.isEmpty()) {
                    LOG.info("Opening the release page " + updateUrl);
                    openUrl(updateUrl);
                }
            }
        });

        hostSegment = CoopTheme.segment("Host");
        guestSegment = CoopTheme.segment("Guest");
        ButtonGroup group = new ButtonGroup();
        group.add(hostSegment);
        group.add(guestSegment);
        hostSegment.addActionListener(event -> onRoleChanged());
        guestSegment.addActionListener(event -> onRoleChanged());
        JPanel segments = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        segments.setOpaque(false);
        segments.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        JPanel pill = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 2));
        pill.setBackground(CoopTheme.CARD);
        pill.setBorder(BorderFactory.createLineBorder(CoopTheme.CARD_BORDER, 1, true));
        pill.add(hostSegment);
        pill.add(guestSegment);
        segments.add(pill);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        header.add(title, c);
        c.gridx = 1;
        c.insets = new Insets(6, 10, 0, 0);
        header.add(version, c);
        c.gridx = 2;
        c.insets = new Insets(2, 12, 0, 0);
        header.add(updateChip, c);
        c.gridx = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        header.add(Box.createHorizontalGlue(), c);
        c.gridx = 4;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        header.add(segments, c);
        return header;
    }

    private Card buildSessionCard() {
        Card card = new Card("Session");
        sessionHint = CoopTheme.muted("");
        card.trailing.add(sessionHint);

        sessionBody = new JPanel(new BorderLayout());
        sessionBody.setOpaque(false);
        hostForm = buildHostForm();
        guestForm = buildGuestForm();
        sessionBody.add(hostForm, BorderLayout.CENTER);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        card.body.add(sessionBody, c);
        return card;
    }

    private JPanel buildHostForm() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        Form form = new Form(panel);

        hostPortField = CoopTheme.textField(String.valueOf(DEFAULT_PORT));
        hostPortField.setText(String.valueOf(DEFAULT_PORT));
        hostPortField.setToolTipText("The TCP and UDP port your partner connects to.");

        hostPasswordField = CoopTheme.passwordField("no password");
        hostPasswordField.setToolTipText("Generated for you. Clear it for an open session. The invite"
                + " carries it.");
        hostPasswordField.getDocument().addDocumentListener(new PasswordWatcher());
        form.pair("Port", hostPortField, "Password", hostPasswordField);

        hostSeedField = CoopTheme.textField("press Generate");
        hostSeedField.setToolTipText("Both games generate the same sector from this. New campaigns"
                + " only.");
        JButton generate = CoopTheme.inline("Generate");
        generate.addActionListener(event -> {
            String seed = CoopSeeds.generate();
            hostSeedField.setText(seed);
            LOG.info("Generated a new seed: " + seed);
            append("New seed " + seed + ". Copy the invite so your partner gets the same one.");
        });
        CoopTheme.trailing(hostSeedField, generate);

        publicAddressField = CoopTheme.textField("press Look up, or type a LAN or VPN address");
        publicAddressField.setToolTipText("What your partner connects to. Overwrite it with a LAN or"
                + " VPN address if that is how you reach each other.");
        JButton lookUp = CoopTheme.inline("Look up");
        lookUp.addActionListener(event -> lookUpPublicAddress(null));
        CoopTheme.trailing(publicAddressField, lookUp);
        form.pair("Seed", hostSeedField, "Your address", publicAddressField);

        sectorSizeBox = combo(SECTOR_SIZES, DEFAULT_SECTOR_SIZE);
        sectorSizeBox.setToolTipText("New campaigns only. The invite carries it to your partner.");
        sectorAgeBox = combo(STAR_AGES, DEFAULT_STAR_AGE);
        sectorAgeBox.setToolTipText("New campaigns only. The invite carries it to your partner.");
        form.pair("Sector size", sectorSizeBox, "Star age", sectorAgeBox);

        invitePreviewField = CoopTheme.textField("fill in the address and seed above");
        invitePreviewField.setEditable(false);
        invitePreviewField.setForeground(CoopTheme.ACCENT);
        copyInviteButton = CoopTheme.inline("Copy");
        copyInviteButton.addActionListener(event -> copyInvite());
        CoopTheme.trailing(invitePreviewField, copyInviteButton);
        form.full("Invite for your partner", invitePreviewField);

        DocumentListener preview = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                refreshInvitePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                refreshInvitePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                refreshInvitePreview();
            }
        };
        hostPortField.getDocument().addDocumentListener(preview);
        hostPortField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updateLaunchGate();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updateLaunchGate();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updateLaunchGate();
            }
        });
        hostPasswordField.getDocument().addDocumentListener(preview);
        hostSeedField.getDocument().addDocumentListener(preview);
        publicAddressField.getDocument().addDocumentListener(preview);
        sectorSizeBox.addActionListener(event -> refreshInvitePreview());
        sectorAgeBox.addActionListener(event -> refreshInvitePreview());
        return panel;
    }

    private JPanel buildGuestForm() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        Form form = new Form(panel);

        guestInviteField = CoopTheme.textField("paste the coop:// line your host sent you");
        JButton paste = CoopTheme.inline("Paste");
        paste.addActionListener(event -> pasteInvite());
        CoopTheme.trailing(guestInviteField, paste);
        guestInviteField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                onGuestInviteTyped();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                onGuestInviteTyped();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                onGuestInviteTyped();
            }
        });
        form.full("Invite from your host", guestInviteField);
        guestInviteNote = CoopTheme.paragraph("Fills the fields below. You can also type them in.");
        guestInviteNote.setBorder(BorderFactory.createEmptyBorder(0, 2, 10, 0));
        form.raw(guestInviteNote);

        guestHostField = CoopTheme.textField("name, IPv4 or IPv6");
        guestPortField = CoopTheme.textField(String.valueOf(DEFAULT_PORT));
        guestPortField.setText(String.valueOf(DEFAULT_PORT));
        form.pair("Host address", guestHostField, "Port", guestPortField);
        DocumentListener gate = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updateLaunchGate();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updateLaunchGate();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updateLaunchGate();
            }
        };
        guestHostField.getDocument().addDocumentListener(gate);
        guestPortField.getDocument().addDocumentListener(gate);

        guestPasswordField = CoopTheme.passwordField("none");
        guestPasswordField.setToolTipText("Has to match the host's exactly.");
        guestSeedField = CoopTheme.textField("from the invite");
        guestSeedField.setEditable(false);
        guestSeedField.setToolTipText("Only used when you start a new campaign.");
        form.pair("Password", guestPasswordField, "Seed", guestSeedField);

        guestSectorSizeField = CoopTheme.textField("");
        guestSectorSizeField.setEditable(false);
        guestSectorSizeField.setText(DEFAULT_SECTOR_SIZE);
        guestSectorSizeField.setToolTipText("From the invite. New campaigns only.");
        guestSectorAgeField = CoopTheme.textField("");
        guestSectorAgeField.setEditable(false);
        guestSectorAgeField.setText(DEFAULT_STAR_AGE);
        guestSectorAgeField.setToolTipText("From the invite. New campaigns only.");
        form.pair("Sector size", guestSectorSizeField, "Star age", guestSectorAgeField);
        return panel;
    }

    private Card buildConnectionCard() {
        Card card = new Card("Connection");
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);

        connectionButton = CoopTheme.secondary("Check my connection");
        connectionButton.addActionListener(event -> {
            if (hostSegment.isSelected()) {
                checkMyConnection();
            } else {
                testConnection();
            }
        });
        chipRow = new JPanel(new CoopTheme.WrapLayout(6, 4));
        chipRow.setOpaque(false);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        row.add(connectionButton, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 12, 0, 0);
        row.add(chipRow, c);

        connectionNote = CoopTheme.paragraph("");
        connectionNote.setBorder(BorderFactory.createEmptyBorder(10, 2, 0, 0));
        connectionNote.setVisible(false);

        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        card.body.add(row, c);
        c.gridy = 1;
        card.body.add(connectionNote, c);
        return card;
    }

    private Card buildAdvancedCard() {
        Card card = new Card("Advanced");
        card.trailing.add(CoopTheme.muted("Defaults shown. Change only with a reason."));
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        Form form = new Form(panel);

        portMappingBox = combo(CoopOptionsRegistry.require(CoopOptionsRegistry.PORT_MAPPING)
                .allowedValues(), registryDefault(CoopOptionsRegistry.PORT_MAPPING));
        portMappingBox.setToolTipText("auto asks your router to forward the port over UPnP. Host"
                + " only.");
        hudCornerBox = combo(CoopOptionsRegistry.require(CoopOptionsRegistry.HUD_CORNER)
                .allowedValues(), registryDefault(CoopOptionsRegistry.HUD_CORNER));
        hudCornerBox.setToolTipText("Where the one-line link status sits on screen. Local only.");
        form.pair("Port mapping", portMappingBox, "Link HUD corner", hudCornerBox);

        reconnectGraceSpinner = spinner(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, 5);
        reconnectGraceSpinner.setToolTipText("How long a dropped link keeps the session alive."
                + " Host decides.");
        bridgePortSpinner = spinner(CoopOptionsRegistry.DEBUG_BRIDGE, 1);
        bridgePortSpinner.setToolTipText("Port for the localhost agent bridge used by the dev"
                + " tooling. 0 means no socket.");
        form.pair("Reconnect grace (seconds)", reconnectGraceSpinner, "Agent bridge port (0 = off)",
                bridgePortSpinner);

        wiretapSampleSpinner = spinner(CoopOptionsRegistry.DEBUG_WIRETAP_SAMPLE, 1);
        wiretapSampleSpinner.setToolTipText("Log every Nth datagram per type when the wiretap is"
                + " on.");
        interactionDelaySpinner = spinner(CoopOptionsRegistry.DEBUG_INTERACTION_DELAY_MS, 100);
        interactionDelaySpinner.setToolTipText("Test instrument: the host holds every interaction"
                + " claim this many ms.");
        form.pair("Wiretap sample (every Nth)", wiretapSampleSpinner,
                "Interaction delay (ms)", interactionDelaySpinner);

        JLabel flagsLabel = CoopTheme.fieldLabel("Developer flags");
        flagsLabel.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 0));
        form.raw(flagsLabel);

        diagnosticsBox = flag("Diagnostics", "Master switch for the dormant diagnostics: orbit"
                + " dumps, dialog state, probes. Verbose log.");
        wiretapBox = flag("Datagram wiretap", "Per-type size histograms against the WAN budget.");
        form.pair(null, diagnosticsBox, null, wiretapBox);
        frameProfileBox = flag("Frame profiler", "Per-frame pump profiler in the log.");
        fullFidelityBox = flag("Full-fidelity guest system", "Kill switch for the full-fidelity"
                + " guest-system driver. On by default; off is a fidelity downgrade.");
        form.pair(null, frameProfileBox, null, fullFidelityBox);
        ffDisableBox = flag("Disable shared fast-forward", "Forces the shared fast-forward lock"
                + " unavailable, the behaviour before Phase 7b.");
        clockDisableBox = flag("Disable clock reconciler", "Turns off calendar drift correction,"
                + " the behaviour before Phase 7c.");
        form.pair(null, ffDisableBox, null, clockDisableBox);
        allowGameVersionMismatchBox = flag("Allow game version mismatch", "Lets the mod run on a"
                + " Starsector version other than the one it was built for. For testing a new"
                + " release candidate before the forks are updated. Unsupported.");
        // The Game version install row reads this checkbox, so it has to be re-run when it changes:
        // ticking it turns that row from a Launch-blocking FAIL into a WARN, and a row that only
        // caught up on the next Refresh would leave the button dead with no visible reason.
        allowGameVersionMismatchBox.addActionListener(event -> refreshInstallRows());
        adoptCampaignBox = flag("Start over inside the host's campaign (guest)", "Overrides the seed"
                + " lock and adopts the host's in-flight campaign id. Discards this guest's co-op"
                + " progress. Never remembered between launches.");
        form.pair(null, allowGameVersionMismatchBox, null, adoptCampaignBox);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        card.body.add(panel, c);
        return card;
    }

    private Card buildInstallCard() {
        Card card = new Card("Install");
        installSummary = new Chip("checking", CoopTheme.INFO);
        card.trailing.add(installSummary);
        JButton refresh = CoopTheme.ghost("Refresh");
        refresh.addActionListener(event -> {
            LOG.info("Install check refreshed by the player");
            refreshInstallRows();
        });
        JButton folder = CoopTheme.ghost("Folder");
        folder.setToolTipText("Point the launcher at a different Starsector install.");
        folder.addActionListener(event -> chooseInstallFolder());
        JButton guide = CoopTheme.ghost("Guide");
        guide.setToolTipText("Open the install guide (INSTALL.md).");
        guide.addActionListener(event -> openInstallDoc());
        card.trailing.add(refresh);
        card.trailing.add(folder);
        card.trailing.add(guide);

        rowsPanel = new JPanel();
        rowsPanel.setOpaque(false);
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));

        showAllButton = CoopTheme.ghost("Show all checks");
        showAllButton.addActionListener(event -> {
            showAllRows = !showAllRows;
            renderRows();
        });

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        card.body.add(rowsPanel, c);
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(4, 0, 0, 0);
        card.body.add(showAllButton, c);
        return card;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new GridBagLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(6, 20, 14, 20));

        launchButton = CoopTheme.primary("LAUNCH");
        launchButton.addActionListener(event -> launch());
        footerHint = CoopTheme.small("Closing this window does not close the game.");

        advancedToggle = CoopTheme.ghost("Advanced");
        advancedToggle.addActionListener(event -> {
            boolean show = !advancedCard.isVisible();
            advancedCard.setVisible(show);
            LOG.info("Advanced settings " + (show ? "shown" : "hidden"));
            advancedCard.getParent().revalidate();
        });
        logToggle = CoopTheme.ghost("Log");
        logToggle.addActionListener(event -> setDrawerVisible(!drawer.isVisible()));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        footer.add(launchButton, c);
        c.gridx = 1;
        c.insets = new Insets(0, 14, 0, 0);
        footer.add(footerHint, c);
        c.gridx = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        footer.add(Box.createHorizontalGlue(), c);
        c.gridx = 3;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        footer.add(advancedToggle, c);
        c.gridx = 4;
        c.insets = new Insets(0, 4, 0, 0);
        footer.add(logToggle, c);
        return footer;
    }

    private JPanel buildDrawer() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CoopTheme.FIELD);
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, CoopTheme.CARD_BORDER));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        toolbar.setOpaque(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 14, 0, 14));
        bugReportButton = CoopTheme.secondary("Save a bug report");
        bugReportButton.addActionListener(event -> saveBugReport());
        includeSaveBox = new JCheckBox("Include my newest save", true);
        includeSaveBox.setOpaque(false);
        includeSaveBox.setForeground(CoopTheme.MUTED);
        JButton openLogFolder = CoopTheme.ghost("Open log folder");
        openLogFolder.addActionListener(event -> {
            if (layout == null) {
                append("There is no install to open a log folder for yet.");
                return;
            }
            LOG.info("Opening the log folder " + layout.starsectorCore());
            openPath(layout.starsectorCore());
        });
        JButton clear = CoopTheme.ghost("Clear");
        clear.addActionListener(event -> statusArea.setText(""));
        toolbar.add(bugReportButton);
        toolbar.add(includeSaveBox);
        toolbar.add(CoopTheme.hgap(8));
        toolbar.add(openLogFolder);
        toolbar.add(clear);

        statusArea = new JTextArea(9, 60);
        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        statusArea.setForeground(CoopTheme.TEXT);
        statusArea.setBackground(CoopTheme.FIELD);
        statusArea.setBorder(BorderFactory.createEmptyBorder(6, 14, 10, 14));
        JScrollPane scroll = new JScrollPane(statusArea);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(600, 190));

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void setDrawerVisible(boolean visible) {
        if (drawer.isVisible() == visible) {
            return;
        }
        drawer.setVisible(visible);
        logToggle.setForeground(visible ? CoopTheme.ACCENT : CoopTheme.MUTED);
        drawer.getParent().revalidate();
    }

    // ---- state ----------------------------------------------------------------------------------

    private void adoptLayout(CoopInstallLayout newLayout) {
        this.layout = newLayout;
        CoopLauncherLogging.configure(newLayout.launcherLog());
        LOG.info("Using install " + newLayout);
        append("Install: " + newLayout.installRoot());
        clearAdoptConsent(newLayout, "a previous launch left it behind");
        this.config = CoopLauncherConfig.read(newLayout.coopOptions());
        if (config.readError() != null) {
            LOG.warn("Settings file unreadable: " + config.readError());
        } else {
            LOG.info("Settings file " + (config.fileExisted() ? "read, keys " + config.keys()
                    : "not present yet"));
        }
        prefill();
        refreshInstallRows();
    }

    private void prefill() {
        String hostPort = config.value(CoopLauncherConfig.HOST_PORT).trim();
        String connectHost = config.value(CoopLauncherConfig.CONNECT_HOST).trim();
        String connectPort = config.value(CoopLauncherConfig.CONNECT_PORT).trim();

        boolean host = !hostPort.isEmpty() || connectHost.isEmpty();
        hostSegment.setSelected(host);
        guestSegment.setSelected(!host);

        hostPortField.setText(hostPort.isEmpty() ? String.valueOf(DEFAULT_PORT) : hostPort);
        guestHostField.setText(connectHost);
        guestPortField.setText(connectPort.isEmpty() ? String.valueOf(DEFAULT_PORT) : connectPort);

        String password = config.value(CoopLauncherConfig.PASSWORD);
        // Prefilling is not the player clearing the field, so the watcher has to stay quiet here or
        // an install with no saved password would never get a generated one.
        writingHostPassword = true;
        try {
            hostPasswordField.setText(password);
        } finally {
            writingHostPassword = false;
        }
        guestPasswordField.setText(password);

        String seed = config.value(CoopLauncherConfig.NEW_GAME_SEED);
        hostSeedField.setText(seed);
        guestSeedField.setText(seed);

        select(portMappingBox, config.value(CoopLauncherConfig.PORT_MAPPING),
                registryDefault(CoopOptionsRegistry.PORT_MAPPING));
        select(hudCornerBox, config.value(CoopLauncherConfig.HUD_CORNER),
                registryDefault(CoopOptionsRegistry.HUD_CORNER));
        select(sectorSizeBox, config.value(CoopLauncherConfig.SECTOR_SIZE), DEFAULT_SECTOR_SIZE);
        select(sectorAgeBox, config.value(CoopLauncherConfig.SECTOR_AGE), DEFAULT_STAR_AGE);
        guestSectorSizeField.setText(orDefault(config.value(CoopLauncherConfig.SECTOR_SIZE),
                DEFAULT_SECTOR_SIZE));
        guestSectorAgeField.setText(orDefault(config.value(CoopLauncherConfig.SECTOR_AGE),
                DEFAULT_STAR_AGE));

        setSpinner(reconnectGraceSpinner, CoopLauncherConfig.RECONNECT_GRACE_SECONDS);
        setSpinner(bridgePortSpinner, CoopLauncherConfig.DEBUG_BRIDGE);
        setSpinner(wiretapSampleSpinner, CoopLauncherConfig.DEBUG_WIRETAP_SAMPLE);
        setSpinner(interactionDelaySpinner, CoopLauncherConfig.DEBUG_INTERACTION_DELAY_MS);
        setFlag(diagnosticsBox, CoopLauncherConfig.DEBUG_DIAGNOSTICS);
        setFlag(wiretapBox, CoopLauncherConfig.DEBUG_WIRETAP);
        setFlag(frameProfileBox, CoopLauncherConfig.DEBUG_FRAME_PROFILE);
        setFlag(fullFidelityBox, CoopLauncherConfig.FULL_FIDELITY_GUEST_SYSTEM);
        setFlag(ffDisableBox, CoopLauncherConfig.FF_DISABLE);
        setFlag(clockDisableBox, CoopLauncherConfig.CLOCK_DISABLE);
        setFlag(allowGameVersionMismatchBox, CoopLauncherConfig.ALLOW_GAME_VERSION_MISMATCH);
        // One-shot consent: never prefilled, so a previous launch's choice cannot repeat itself.
        adoptCampaignBox.setSelected(false);
        onRoleChanged();
    }

    private void onRoleChanged() {
        boolean host = hostSegment.isSelected();
        sessionBody.removeAll();
        sessionBody.add(host ? hostForm : guestForm, BorderLayout.CENTER);
        sessionHint.setText(host ? "You run the world. Your partner joins it."
                : "You join your partner's world.");
        connectionButton.setText(host ? "Check my connection" : "Test connection");
        connectionButton.setToolTipText(host
                ? "Asks your router to open the port the way the game will, then waits for your"
                        + " partner's test."
                : "Reaches the host's launcher. Ask your host to open theirs and press Check first.");
        setChips(List.of());
        note("");
        cancelConnectionCheck("the role changed");
        closeListener("the role changed");
        if (host) {
            maybeGenerateHostPassword();
            maybeGenerateHostSeed();
            refreshInvitePreview();
        }
        updateLaunchGate();
        sessionCard.revalidate();
        sessionCard.repaint();
    }

    /** A host always has a seed: a new campaign without one cannot be matched by the guest. */
    private void maybeGenerateHostSeed() {
        if (!hostSeedField.getText().trim().isEmpty()) {
            return;
        }
        String seed = CoopSeeds.generate();
        hostSeedField.setText(seed);
        LOG.info("Generated a seed for the host: " + seed);
        append("Generated seed " + seed + ". It only matters for a new campaign; the invite carries"
                + " it.");
    }

    /**
     * Enables LAUNCH only when the fields a launch needs are there, and says in the footer what is
     * missing otherwise. Install problems and a running game also hold it.
     */
    private void updateLaunchGate() {
        if (launchButton == null || footerHint == null) {
            return;
        }
        String reason = launchBlockedReason();
        launchButton.setEnabled(reason == null);
        if (gameRunning) {
            footerHint.setText("The game is running. Closing this window does not close it.");
            footerHint.setForeground(CoopTheme.MUTED);
        } else if (reason != null) {
            footerHint.setText(reason);
            footerHint.setForeground(CoopTheme.WARN);
        } else {
            footerHint.setText("Closing this window does not close the game.");
            footerHint.setForeground(CoopTheme.MUTED);
        }
        launchButton.setToolTipText(reason);
    }

    private String launchBlockedReason() {
        if (gameRunning) {
            return "Starsector is already running from this launcher.";
        }
        if (layout == null) {
            return "Point the launcher at your Starsector install first (Install, Folder).";
        }
        if (CoopInstallCheck.blocked(installRows)) {
            return "Fix the install problems listed above, then press Refresh.";
        }
        if (hostSegment.isSelected()) {
            if (!validPort(hostPortField.getText())) {
                return "The port has to be a number between 1 and 65535.";
            }
            return null;
        }
        if (guestHostField.getText().trim().isEmpty()) {
            return "Paste the invite from your host, or type the host address in.";
        }
        if (!validPort(guestPortField.getText())) {
            return "The port has to be a number between 1 and 65535.";
        }
        return null;
    }

    private static boolean validPort(String text) {
        try {
            int port = Integer.parseInt(text == null ? "" : text.trim());
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * Fills an empty host password field with a generated one. Does nothing once the player has
     * emptied the field themselves, and nothing when they have already typed something.
     */
    private void maybeGenerateHostPassword() {
        if (hostPasswordCleared || !password(hostPasswordField).isEmpty()) {
            return;
        }
        writingHostPassword = true;
        try {
            hostPasswordField.setText(CoopPasswords.generate());
        } finally {
            writingHostPassword = false;
        }
        // The value never reaches the log. The whole point of it is that only the two players see it.
        LOG.info("Generated a host password of " + CoopPasswords.LENGTH + " characters;"
                + " the value is not logged");
        append("Generated a password for you. The invite carries it. Clear the field if you would"
                + " rather have no password.");
    }

    /** Notices the player emptying the host password field, so nothing refills it afterwards. */
    private final class PasswordWatcher implements DocumentListener {

        @Override
        public void insertUpdate(DocumentEvent event) {
            check();
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            check();
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
            check();
        }

        private void check() {
            if (writingHostPassword || hostPasswordCleared) {
                return;
            }
            if (password(hostPasswordField).isEmpty()) {
                hostPasswordCleared = true;
                LOG.info("The player emptied the host password field; it stays empty for this run");
            }
        }
    }

    /** Keeps the read-only invite line in step with the host fields. */
    private void refreshInvitePreview() {
        if (invitePreviewField == null) {
            return;
        }
        String invite = buildInvite(false);
        invitePreviewField.setText(invite == null ? "" : invite);
        copyInviteButton.setEnabled(invite != null);
    }

    /**
     * The invite for the current host fields, or {@code null} when a field is missing or wrong.
     * With {@code loud} the reason is shown; the live preview stays quiet.
     */
    private String buildInvite(boolean loud) {
        String address = publicAddressField.getText().trim();
        String portText = hostPortField.getText().trim();
        String seed = hostSeedField.getText().trim();
        if (address.isEmpty() || portText.isEmpty()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            if (loud) {
                fail("The port has to be a number between 1 and 65535, not \"" + portText + "\".");
            }
            return null;
        }
        if (port < 1 || port > 65535) {
            if (loud) {
                fail("The port has to be between 1 and 65535.");
            }
            return null;
        }
        String seedProblem = CoopSeeds.validate(seed);
        if (seedProblem != null) {
            if (loud) {
                fail("That seed is not usable: " + seedProblem + ". Press Generate for a fresh one.");
            }
            return null;
        }
        try {
            return CoopInvite.format(address, port, seed, password(hostPasswordField),
                    selected(sectorSizeBox), selected(sectorAgeBox));
        } catch (IllegalArgumentException ex) {
            if (loud) {
                fail("Could not build an invite: " + ex.getMessage());
            }
            return null;
        }
    }

    private void refreshInstallRows() {
        if (layout == null) {
            return;
        }
        config = CoopLauncherConfig.read(layout.coopOptions());
        installRows = CoopInstallCheck.inspect(layout, config.readError(),
                allowGameVersionMismatchBox != null && allowGameVersionMismatchBox.isSelected());
        for (CoopInstallCheck.Row row : installRows) {
            LOG.info("Install check " + row);
        }
        renderRows();
        int fails = 0;
        int warns = 0;
        for (CoopInstallCheck.Row row : installRows) {
            if (row.status() == CoopInstallCheck.Status.FAIL) {
                fails++;
            } else if (row.status() == CoopInstallCheck.Status.WARN) {
                warns++;
            }
        }
        append("Install check: " + fails + " problem(s), " + warns + " warning(s).");
        for (CoopInstallCheck.Row row : installRows) {
            if (row.status() != CoopInstallCheck.Status.OK) {
                append("  " + row);
            }
        }
    }

    /** Redraws the install card from {@link #installRows} plus the update row, if one has landed. */
    private void renderRows() {
        List<CoopInstallCheck.Row> all = new ArrayList<>(installRows);
        if (updateRow != null) {
            all.add(updateRow);
        }
        int fails = 0;
        int warns = 0;
        for (CoopInstallCheck.Row row : all) {
            if (row.status() == CoopInstallCheck.Status.FAIL) {
                fails++;
            } else if (row.status() == CoopInstallCheck.Status.WARN) {
                warns++;
            }
        }
        if (fails > 0) {
            installSummary.set(fails + (fails == 1 ? " problem" : " problems")
                    + (warns > 0 ? ", " + warns + (warns == 1 ? " warning" : " warnings") : ""),
                    CoopTheme.FAIL);
        } else if (warns > 0) {
            installSummary.set(warns + (warns == 1 ? " warning" : " warnings"), CoopTheme.WARN);
        } else if (all.isEmpty()) {
            installSummary.set("no install", CoopTheme.INFO);
        } else {
            installSummary.set("all " + all.size() + " checks passed", CoopTheme.OK);
        }
        updateLaunchGate();

        rowsPanel.removeAll();
        int hidden = 0;
        for (CoopInstallCheck.Row row : all) {
            boolean interesting = row.status() != CoopInstallCheck.Status.OK;
            if (!interesting && !showAllRows) {
                hidden++;
                continue;
            }
            JButton trailing = null;
            if (row == updateRow && !updateUrl.isEmpty()) {
                trailing = CoopTheme.inline("Open release page");
                trailing.addActionListener(event -> {
                    LOG.info("Opening the release page " + updateUrl);
                    openUrl(updateUrl);
                });
            }
            rowsPanel.add(renderRow(row, trailing));
        }
        showAllButton.setText(showAllRows ? "Hide passed checks"
                : "Show all checks (" + hidden + " passed)");
        showAllButton.setVisible(hidden > 0 || showAllRows);
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    private JComponent renderRow(CoopInstallCheck.Row row, JComponent trailing) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(5, 2, 0, 10);
        panel.add(new Dot(CoopTheme.statusColor(row.status())), c);

        JLabel label = new JLabel(row.label());
        label.setForeground(CoopTheme.TEXT);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 8);
        panel.add(label, c);

        JLabel detail = CoopTheme.muted(row.detail());
        c.gridx = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        panel.add(detail, c);

        if (trailing != null) {
            c.gridx = 3;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.insets = new Insets(0, 8, 0, 0);
            panel.add(trailing, c);
        }
        if (!row.fix().isEmpty()) {
            JLabel fix = new JLabel("<html><body style='width: 460px'>" + escape(row.fix())
                    + "</body></html>");
            fix.setForeground(CoopTheme.MUTED);
            fix.setFont(fix.getFont().deriveFont(Font.ITALIC, (float) fix.getFont().getSize() - 1f));
            c = new GridBagConstraints();
            c.gridx = 1;
            c.gridy = 1;
            c.gridwidth = 3;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(2, 0, 0, 0);
            panel.add(fix, c);
        }
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private void setChips(List<Chip> chips) {
        chipRow.removeAll();
        for (Chip chip : chips) {
            chipRow.add(chip);
        }
        chipRow.revalidate();
        chipRow.repaint();
    }

    private void note(String text) {
        connectionNote.setText(text);
        connectionNote.setVisible(!text.isEmpty());
        connectionNote.getParent().revalidate();
    }

    // ---- actions --------------------------------------------------------------------------------

    private void copyInvite() {
        LOG.info("Copy invite pressed");
        String address = publicAddressField.getText().trim();
        if (address.isEmpty()) {
            append("Looking up your public address first.");
            lookUpPublicAddress(this::finishCopyInvite, true);
            return;
        }
        finishCopyInvite();
    }

    private void finishCopyInvite() {
        String address = publicAddressField.getText().trim();
        if (address.isEmpty()) {
            fail("No address to put in the invite. Press Look up, or type one in, then Copy again.");
            return;
        }
        String invite = buildInvite(true);
        if (invite == null) {
            return;
        }
        setClipboard(invite);
        LOG.info("Invite copied for " + address + ":" + hostPortField.getText().trim() + " seed="
                + hostSeedField.getText().trim() + " password="
                + (password(hostPasswordField).isEmpty() ? "none" : "set"));
        append("Invite copied to the clipboard. Send your partner this line:");
        append("  " + invite);
        note("Invite copied. Send it to your partner, then press Check my connection.");
    }

    private void pasteInvite() {
        LOG.info("Paste invite pressed");
        String text = readClipboard().trim();
        if (text.isEmpty()) {
            fail("The clipboard is empty. Copy the invite line your host sent you first.");
            return;
        }
        writingGuestInvite = true;
        try {
            guestInviteField.setText(text);
        } finally {
            writingGuestInvite = false;
        }
        applyInviteText(text, true);
    }

    private void onGuestInviteTyped() {
        if (writingGuestInvite) {
            return;
        }
        String text = guestInviteField.getText().trim();
        if (text.isEmpty()) {
            guestInviteNote.setForeground(CoopTheme.MUTED);
            guestInviteNote.setText("Fills the fields below. You can also type them in.");
            return;
        }
        applyInviteText(text, false);
    }

    private void applyInviteText(String text, boolean loud) {
        CoopInvite.Parsed parsed = CoopInvite.parse(text);
        if (!parsed.ok()) {
            guestInviteNote.setForeground(CoopTheme.FAIL);
            guestInviteNote.setText("Not a usable invite: " + parsed.error());
            if (loud) {
                LOG.warn("Invite could not be parsed: " + parsed.error());
                fail("That is not a usable invite: " + parsed.error());
            }
            return;
        }
        CoopInvite invite = parsed.invite();
        guestHostField.setText(invite.host());
        guestPortField.setText(String.valueOf(invite.port()));
        guestPasswordField.setText(invite.password());
        guestSeedField.setText(invite.seed());
        guestSectorSizeField.setText(orDefault(invite.sectorSize(), DEFAULT_SECTOR_SIZE));
        guestSectorAgeField.setText(orDefault(invite.sectorAge(), DEFAULT_STAR_AGE));
        String summary = "Invite read. Host " + invite.host() + ":" + invite.port()
                + (invite.seed().isEmpty() ? ", no seed" : ", seed " + invite.seed())
                + ", " + guestSectorSizeField.getText() + " sector, " + guestSectorAgeField.getText()
                + " stars"
                + (invite.password().isEmpty() ? ", no password" : ", password set") + ".";
        guestInviteNote.setForeground(CoopTheme.OK);
        guestInviteNote.setText(summary);
        LOG.info("Invite accepted: " + invite);
        append(summary);
    }

    private void lookUpPublicAddress(Runnable then) {
        lookUpPublicAddress(then, false);
    }

    /**
     * Looks the public address up on the worker, which takes up to ten seconds on a slow network.
     *
     * @param automatic true for a lookup the launcher started by itself; those give way to whatever
     *                  the player typed while the answer was in flight, where an explicit "Look up"
     *                  press means "overwrite what is in the field"
     */
    private void lookUpPublicAddress(Runnable then, boolean automatic) {
        LOG.info("Public address lookup started");
        append("Looking up your public address.");
        note("Looking up your public address.");
        String textWhenStarted = publicAddressField.getText();
        background.submit(() -> {
            CoopPublicAddress.Lookup result = CoopPublicAddress.lookup();
            SwingUtilities.invokeLater(() -> {
                if (result.ok()) {
                    if (!shouldApplyLookedUpAddress(automatic, textWhenStarted,
                            publicAddressField.getText())) {
                        LOG.info("Public address lookup returned " + result.address()
                                + "; keeping the address typed while it ran");
                        append("Your public address is " + result.address() + ", but you typed "
                                + publicAddressField.getText().trim()
                                + " while the lookup ran, so that is what the invite uses.");
                        note("Using the address you typed, not the public one.");
                        if (then != null) {
                            then.run();
                        }
                        return;
                    }
                    publicAddressField.setText(result.address());
                    LOG.info("Public address lookup returned " + result.address());
                    append("Your public address is " + result.address()
                            + ". Overwrite it if you connect over a LAN or a VPN.");
                    note("Your public address is " + result.address() + ". Overwrite it for a LAN"
                            + " or VPN session.");
                } else {
                    LOG.warn("Public address lookup failed: " + result.error());
                    append(result.error());
                    note(result.error());
                }
                if (then != null) {
                    then.run();
                }
            });
        });
    }

    private void checkMyConnection() {
        String blocked = connectionCheckBlockedReason(gameRunning);
        if (blocked != null) {
            LOG.warn("Check my connection refused: " + blocked);
            fail(blocked);
            return;
        }
        Integer port = parsePort(hostPortField.getText(), "port");
        if (port == null) {
            return;
        }
        LOG.info("Check my connection pressed for port " + port);
        connectionButton.setEnabled(false);
        cancelConnectionCheck("a new connection check started");
        int generation = checkGeneration;
        closeListener("a new connection check started");
        append("Checking port " + port + ". This takes a few seconds.");
        Chip working = new Chip("asking the router", CoopTheme.INFO);
        setChips(List.of(working));
        note("Asking your router to open port " + port + ", the way the game does at startup.");

        boolean mappingEnabled = !"off".equalsIgnoreCase(selected(portMappingBox));
        CoopPortMapper mapper = CoopPortMapper.start(port, mappingEnabled, System::currentTimeMillis);
        long started = System.currentTimeMillis();
        AtomicInteger ticks = new AtomicInteger();
        checkTimer = new javax.swing.Timer(CHECK_TICK_MILLIS, null);
        checkTimer.addActionListener(event -> {
            long now = System.currentTimeMillis();
            mapper.tick(now);
            ticks.incrementAndGet();
            boolean timedOut = now - started > CHECK_TIMEOUT_MILLIS;
            if (!mapper.result().finished() && !timedOut) {
                return;
            }
            checkTimer.stop();
            checkTimer = null;
            CoopPortMapper.Result result = mapper.result();
            if (timedOut && !result.finished()) {
                append("The router did not answer within 20 seconds; reporting what is known.");
            }
            LOG.info("Port mapper finished after " + ticks.get() + " ticks: tier=" + result.tier()
                    + " external=" + result.externalEndpoint()
                    + " failure=" + (result.failureText().isEmpty() ? "none" : result.failureText()));
            String report = CoopConnectionDoctor.hostReport(port, result,
                    !password(hostPasswordField).isEmpty(), 1);
            for (String line : report.split("\n", -1)) {
                append(line);
            }
            if (result.mapped() && !result.cgnat() && publicAddressField.getText().trim().isEmpty()) {
                publicAddressField.setText(result.externalAddress());
            }
            showHostChips(port, result, false);
            // shutdown() drives its own bounded release loop (up to 1.2 s of busy waiting against
            // the injected clock), so it has to leave the event dispatch thread. The mapper is not
            // being ticked any more at this point, so handing it over is a clean transfer.
            background.submit(() -> {
                mapper.shutdown();
                SwingUtilities.invokeLater(() -> {
                    append("Released the router mapping so the game can make its own at startup.");
                    connectionButton.setEnabled(true);
                    if (!checkResultStillApplies(generation, checkGeneration, gameRunning,
                            hostSegment.isSelected())) {
                        // LAUNCH, a role switch or a newer check happened while the router was
                        // being asked. Binding the port now would take it from whoever owns it.
                        LOG.info("Connection check " + generation + " is no longer current; not"
                                + " opening the launcher listener on port " + port);
                        return;
                    }
                    boolean listening = openListener(port);
                    showHostChips(port, result, listening);
                });
            });
        });
        checkTimer.start();
    }

    private void showHostChips(int port, CoopPortMapper.Result result, boolean listening) {
        List<Chip> chips = new ArrayList<>();
        if (result.mapped()) {
            chips.add(new Chip(tierName(result.tier()) + " mapped", CoopTheme.OK));
            // The router can map the port and still report no external address of its own (a
            // bridged box, a WAN link that is down), in which case there is no endpoint to show.
            chips.add(new Chip(result.externalEndpoint().isEmpty()
                    ? "external address unknown"
                    : result.externalEndpoint(),
                    result.cgnat() || result.externalEndpoint().isEmpty() ? CoopTheme.WARN
                    : CoopTheme.OK));
            if (result.cgnat()) {
                chips.add(new Chip("carrier-grade NAT", CoopTheme.WARN));
            }
        } else if (result.tier() == CoopPortMapper.Tier.NONE && result.failureText().isEmpty()) {
            chips.add(new Chip("mapping off", CoopTheme.INFO));
        } else {
            chips.add(new Chip("no mapping", CoopTheme.WARN));
        }
        if (listening) {
            chips.add(new Chip("listening on " + port, CoopTheme.OK));
        }
        setChips(chips);
        if (result.mapped() && result.externalEndpoint().isEmpty()) {
            note("Your router mapped port " + port + ", but it reports no outside address of its own"
                    + " (its WAN link may be down). There is nothing to share yet; details in the log.");
        } else if (result.mapped() && !result.cgnat()) {
            note("Your router opened " + result.externalEndpoint() + ". Copy the invite and ask your"
                    + " partner to press Test connection. The full doctor block is in the log.");
        } else if (result.cgnat()) {
            note("Your router answered, but its outside address is not public (carrier-grade NAT)."
                    + " A VPN or IPv6 is the way through. Details in the log.");
        } else if (!result.failureText().isEmpty()) {
            note(result.failureText() + " Forward port " + port + " on your router by hand, or use a"
                    + " VPN. Details in the log.");
        } else {
            note("Port mapping is off. Forward port " + port + " by hand, or use a VPN or LAN"
                    + " address.");
        }
        setDrawerVisible(true);
    }

    private boolean openListener(int port) {
        try {
            listener = CoopLauncherProbe.HostListener.open(port, launcherVersion);
            LOG.info("Launcher listener open on port " + listener.port());
            append("Waiting for the guest's test on port " + listener.port()
                    + ". This stops when you press Launch.");
            return true;
        } catch (Exception ex) {
            LOG.warn("Could not hold port " + port + " for the guest's test", ex);
            append("Port " + port + " is already in use, so the guest cannot test against this"
                    + " launcher. Is the game already running?");
            note("Port " + port + " is already in use, so your partner cannot test against this"
                    + " launcher. Is the game already running?");
            return false;
        }
    }

    private void testConnection() {
        String host = guestHostField.getText().trim();
        if (host.isEmpty()) {
            fail("Paste the invite, or type the host's address in.");
            return;
        }
        Integer port = parsePort(guestPortField.getText(), "port");
        if (port == null) {
            return;
        }
        LOG.info("Test connection pressed for " + host + ":" + port);
        connectionButton.setEnabled(false);
        append("Testing " + host + ":" + port + ".");
        setChips(List.of(new Chip("reaching " + host + ":" + port, CoopTheme.INFO)));
        note("");
        background.submit(() -> {
            CoopLauncherProbe.Result result = CoopLauncherProbe.GuestProber.probe(host, port);
            SwingUtilities.invokeLater(() -> {
                LOG.info("Probe result tcp=" + result.tcpReachable()
                        + " launcher=" + result.launcherAnswered()
                        + " version=" + result.launcherVersion()
                        + " udp=" + result.udpEchoed()
                        + " rtt=" + result.rttMillis());
                append("  TCP reachable       " + yesNo(result.tcpReachable()));
                append("  launcher answered   " + yesNo(result.launcherAnswered())
                        + (result.launcherVersion().isEmpty() ? "" : " (version "
                        + result.launcherVersion() + ")"));
                append("  UDP echoed          " + yesNo(result.udpEchoed()));
                append("  round trip          " + (result.rttMillis() < 0 ? "not measured"
                        : result.rttMillis() + " ms"));
                append(result.message());
                List<Chip> chips = new ArrayList<>();
                chips.add(new Chip("TCP", result.tcpReachable() ? CoopTheme.OK : CoopTheme.FAIL));
                chips.add(new Chip(result.launcherAnswered()
                        ? "launcher " + result.launcherVersion() : "launcher",
                        result.launcherAnswered() ? CoopTheme.OK
                                : result.tcpReachable() ? CoopTheme.WARN : CoopTheme.FAIL));
                chips.add(new Chip("UDP", result.udpEchoed() ? CoopTheme.OK
                        : result.launcherAnswered() ? CoopTheme.FAIL : CoopTheme.INFO));
                chips.add(new Chip(result.rttMillis() < 0 ? "no round trip"
                        : result.rttMillis() + " ms", result.rttMillis() < 0 ? CoopTheme.INFO
                        : CoopTheme.OK));
                setChips(chips);
                note(result.message());
                connectionButton.setEnabled(true);
            });
        });
    }

    private void saveBugReport() {
        if (layout == null) {
            append("Point the launcher at your Starsector install first.");
            return;
        }
        boolean includeSave = includeSaveBox.isSelected();
        String role = hostSegment.isSelected() ? "host" : "guest";
        LOG.info("Save a bug report pressed; role=" + role + " includeSave=" + includeSave);
        bugReportButton.setEnabled(false);
        append("Packing a bug report. A large game log takes a few seconds.");
        CoopInstallLayout target = layout;
        background.submit(() -> {
            CoopBugReport.Result result = null;
            Exception failure = null;
            try {
                result = CoopBugReport.write(target, role, includeSave);
            } catch (Exception ex) {
                failure = ex;
            }
            CoopBugReport.Result finished = result;
            Exception thrown = failure;
            SwingUtilities.invokeLater(() -> {
                bugReportButton.setEnabled(true);
                if (thrown != null) {
                    LOG.error("Could not write the bug report", thrown);
                    fail("Could not write the bug report: " + thrown.getMessage());
                    return;
                }
                LOG.info("Bug report written to " + finished.zip() + " with "
                        + finished.entries().size() + " entries; missing " + finished.missing()
                        + "; notes " + finished.notes());
                append("Saved " + finished.zip() + ". It contains your public address from the"
                        + " doctor block and the last two game logs; attach both players' zips to"
                        + " the report.");
                if (finished.saveInFlight()) {
                    append("The newest save was still being written, run the report again in a"
                            + " moment.");
                }
                openPath(finished.zip().getParentFile());
            });
        });
    }

    /**
     * Asks GitHub whether there is a newer release, on its own thread so a slow answer cannot hold
     * up the worker the buttons use. Every failure lands on a neutral row and nothing else changes.
     */
    private void startUpdateCheck() {
        String version = launcherVersion;
        Thread thread = new Thread(() -> {
            CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.check(version);
            SwingUtilities.invokeLater(() -> {
                updateRow = CoopUpdateCheck.row(outcome);
                boolean available = outcome.kind() == CoopUpdateCheck.Kind.UPDATE_AVAILABLE;
                updateUrl = available ? outcome.url() : "";
                LOG.info("Update check " + updateRow);
                renderRows();
                updateChip.set("update " + outcome.version() + " available", CoopTheme.WARN);
                updateChip.setToolTipText("Both players must install the same release. Click to open"
                        + " the release page.");
                updateChip.setVisible(available);
                if (available) {
                    append("Version " + outcome.version() + " is out. Both of you have to be on the"
                            + " same release: " + outcome.url());
                }
            });
        }, "coop-launcher-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void launch() {
        LOG.info("Launch pressed");
        if (layout == null) {
            fail("Point the launcher at your Starsector install first.");
            return;
        }
        refreshInstallRows();
        if (CoopInstallCheck.blocked(installRows)) {
            List<String> problems = new ArrayList<>();
            for (CoopInstallCheck.Row row : installRows) {
                if (row.status() == CoopInstallCheck.Status.FAIL) {
                    problems.add(row.label() + " - " + row.detail());
                }
            }
            LOG.warn("Launch refused; unresolved problems: " + problems);
            fail("The install still has " + problems.size() + " problem(s) that would stop a co-op"
                    + " session:\n\n" + String.join("\n", problems)
                    + "\n\nFix them and press Refresh.");
            return;
        }

        boolean host = hostSegment.isSelected();
        Map<String, String> owned = new LinkedHashMap<>();
        if (host) {
            Integer port = parsePort(hostPortField.getText(), "port");
            if (port == null) {
                return;
            }
            owned.put(CoopLauncherConfig.HOST_PORT, String.valueOf(port));
            owned.put(CoopLauncherConfig.PASSWORD, password(hostPasswordField));
            String seed = hostSeedField.getText().trim();
            String seedProblem = CoopSeeds.validate(seed);
            if (seedProblem != null) {
                fail("That seed is not usable: " + seedProblem);
                return;
            }
            owned.put(CoopLauncherConfig.NEW_GAME_SEED, seed);
        } else {
            String address = guestHostField.getText().trim();
            if (address.isEmpty()) {
                fail("Paste the invite, or type the host's address in.");
                return;
            }
            Integer port = parsePort(guestPortField.getText(), "port");
            if (port == null) {
                return;
            }
            owned.put(CoopLauncherConfig.CONNECT_HOST, address);
            owned.put(CoopLauncherConfig.CONNECT_PORT, String.valueOf(port));
            owned.put(CoopLauncherConfig.PASSWORD, password(guestPasswordField));
            String seed = guestSeedField.getText().trim();
            String seedProblem = CoopSeeds.validate(seed);
            if (seedProblem != null) {
                fail("The seed from the invite is not usable: " + seedProblem);
                return;
            }
            owned.put(CoopLauncherConfig.NEW_GAME_SEED, seed);
        }
        owned.put(CoopLauncherConfig.PORT_MAPPING, selected(portMappingBox));
        owned.put(CoopLauncherConfig.RECONNECT_GRACE_SECONDS,
                String.valueOf(reconnectGraceSpinner.getValue()));
        owned.put(CoopLauncherConfig.HUD_CORNER, selected(hudCornerBox));
        if (host) {
            owned.put(CoopLauncherConfig.SECTOR_SIZE, selected(sectorSizeBox));
            owned.put(CoopLauncherConfig.SECTOR_AGE, selected(sectorAgeBox));
        } else {
            owned.put(CoopLauncherConfig.SECTOR_SIZE,
                    orDefault(guestSectorSizeField.getText(), DEFAULT_SECTOR_SIZE));
            owned.put(CoopLauncherConfig.SECTOR_AGE,
                    orDefault(guestSectorAgeField.getText(), DEFAULT_STAR_AGE));
        }
        // Flags are written only when they differ from the registry default, so the file stays
        // readable and a default never masquerades as a deliberate choice.
        owned.put(CoopLauncherConfig.DEBUG_BRIDGE, nonDefault(bridgePortSpinner, CoopLauncherConfig.DEBUG_BRIDGE));
        owned.put(CoopLauncherConfig.DEBUG_WIRETAP_SAMPLE,
                nonDefault(wiretapSampleSpinner, CoopLauncherConfig.DEBUG_WIRETAP_SAMPLE));
        owned.put(CoopLauncherConfig.DEBUG_INTERACTION_DELAY_MS,
                nonDefault(interactionDelaySpinner, CoopLauncherConfig.DEBUG_INTERACTION_DELAY_MS));
        owned.put(CoopLauncherConfig.DEBUG_DIAGNOSTICS, nonDefault(diagnosticsBox, CoopLauncherConfig.DEBUG_DIAGNOSTICS));
        owned.put(CoopLauncherConfig.DEBUG_WIRETAP, nonDefault(wiretapBox, CoopLauncherConfig.DEBUG_WIRETAP));
        owned.put(CoopLauncherConfig.DEBUG_FRAME_PROFILE, nonDefault(frameProfileBox, CoopLauncherConfig.DEBUG_FRAME_PROFILE));
        owned.put(CoopLauncherConfig.FULL_FIDELITY_GUEST_SYSTEM,
                nonDefault(fullFidelityBox, CoopLauncherConfig.FULL_FIDELITY_GUEST_SYSTEM));
        owned.put(CoopLauncherConfig.FF_DISABLE, nonDefault(ffDisableBox, CoopLauncherConfig.FF_DISABLE));
        owned.put(CoopLauncherConfig.CLOCK_DISABLE, nonDefault(clockDisableBox, CoopLauncherConfig.CLOCK_DISABLE));
        owned.put(CoopLauncherConfig.ALLOW_GAME_VERSION_MISMATCH,
                nonDefault(allowGameVersionMismatchBox, CoopLauncherConfig.ALLOW_GAME_VERSION_MISMATCH));
        owned.put(CoopLauncherConfig.ADOPT_CAMPAIGN_ID, nonDefault(adoptCampaignBox, CoopLauncherConfig.ADOPT_CAMPAIGN_ID));
        List<String> flagsOn = new ArrayList<>();
        for (Map.Entry<String, String> entry : owned.entrySet()) {
            if (entry.getKey().startsWith("coop.debug.") || entry.getKey().equals(CoopLauncherConfig.FF_DISABLE)
                    || entry.getKey().equals(CoopLauncherConfig.CLOCK_DISABLE)
                    || entry.getKey().equals(CoopLauncherConfig.FULL_FIDELITY_GUEST_SYSTEM)
                    || entry.getKey().equals(CoopLauncherConfig.ALLOW_GAME_VERSION_MISMATCH)
                    || entry.getKey().equals(CoopLauncherConfig.ADOPT_CAMPAIGN_ID)) {
                if (!entry.getValue().isBlank()) {
                    flagsOn.add(entry.getKey() + "=" + entry.getValue());
                }
            }
        }
        if (!flagsOn.isEmpty()) {
            LOG.info("Developer flags for this launch: " + flagsOn);
            append("Developer flags: " + String.join(" ", flagsOn));
        }

        try {
            config.write(layout.coopOptions(), host, owned);
        } catch (Exception ex) {
            LOG.error("Could not write " + layout.coopOptions(), ex);
            fail("Could not save your settings to " + layout.coopOptions() + ":\n\n"
                    + ex.getMessage());
            return;
        }
        LOG.info("Wrote " + layout.coopOptions() + " as " + (host ? "HOST" : "GUEST")
                + " with keys " + owned.keySet());
        append("Settings saved to " + layout.coopOptions() + ".");

        cancelConnectionCheck("the game is starting");
        closeListener("the game is starting");
        try {
            gameProcess = CoopGameProcess.launch(layout);
        } catch (Exception ex) {
            LOG.error("Could not start starsector.exe", ex);
            fail("Could not start the game:\n\n" + ex.getMessage());
            return;
        }
        long pid = gameProcess.pid();
        LOG.info("Started starsector.exe pid " + pid);
        append("Starsector started (pid " + pid + "). This window keeps showing the co-op lines from"
                + " the game log.");
        launchButton.setText("RUNNING");
        gameRunning = true;
        updateLaunchGate();
        CoopInstallLayout launched = layout;
        gameProcess.onExit().thenAccept(process -> {
            // Off the event dispatch thread, and before the UI catches up: the tick meant "this
            // launch", so the consent goes as soon as the launch that consumed it is over.
            clearAdoptConsent(launched, "the launch that used it has ended");
            SwingUtilities.invokeLater(() -> {
                LOG.info("starsector.exe exited with code " + process.exitValue());
                append("Starsector exited (code " + process.exitValue() + ").");
                launchButton.setText("LAUNCH");
                gameRunning = false;
                updateLaunchGate();
            });
        });
        setDrawerVisible(true);
        startLogTail();
    }

    private void startLogTail() {
        if (logTail != null) {
            logTail.close();
            logTail = null;
        }
        File log = layout.starsectorLog();
        logTail = CoopLogTail.start(log, line -> SwingUtilities.invokeLater(() -> append(line)));
        LOG.info("Tailing " + log);
        append("Tailing " + log + ".");
    }

    private void chooseInstallFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Pick your Starsector folder (the one with starsector.exe in it)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (layout != null) {
            chooser.setCurrentDirectory(layout.installRoot());
        }
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File chosen = chooser.getSelectedFile();
        LOG.info("Player picked the install folder " + chosen);
        if (!CoopInstallLayout.looksLikeInstall(chosen)) {
            fail(chosen + " does not hold starsector.exe, vmparams and starsector-core, so it is not"
                    + " a Starsector install.");
            return;
        }
        adoptLayout(CoopInstallLayout.ofInstallRoot(chosen));
    }

    private void openInstallDoc() {
        if (layout == null) {
            append("Point the launcher at your Starsector install first.");
            return;
        }
        File doc = layout.installDoc();
        LOG.info("Opening " + doc);
        if (!doc.isFile()) {
            append("INSTALL.md is not in this copy of the mod. It is in the download, at"
                    + " docs/player/INSTALL.md.");
            setDrawerVisible(true);
            return;
        }
        openPath(doc);
    }

    private void shutdown() {
        LOG.info("Launcher window closing");
        cancelConnectionCheck("the launcher is closing");
        closeListener("the launcher is closing");
        if (logTail != null) {
            logTail.close();
            logTail = null;
        }
        background.shutdownNow();
        // The game, if one was started, keeps running on purpose.
        frame.dispose();
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * Takes the one-shot "start over inside the host's campaign" consent back out of the settings
     * file. The checkbox is never prefilled, but the key it writes is read by the game at every
     * application load, so a launch that consumed it has to clear it again - otherwise the next
     * start, including one made by double-clicking starsector.exe, adopts the host's campaign with
     * nobody consenting to it.
     */
    private void clearAdoptConsent(CoopInstallLayout target, String reason) {
        if (target == null) {
            return;
        }
        try {
            if (CoopLauncherConfig.clearAdoptCampaignConsent(target.coopOptions())) {
                LOG.info("Cleared " + CoopLauncherConfig.ADOPT_CAMPAIGN_ID + " from "
                        + target.coopOptions() + " because " + reason);
            }
        } catch (Exception ex) {
            LOG.warn("Could not clear " + CoopLauncherConfig.ADOPT_CAMPAIGN_ID + " from "
                    + target.coopOptions(), ex);
        }
    }

    /**
     * Stops a connection check in flight and makes sure a result already on its way back cannot
     * open the listener. Everything that takes the co-op port away from a check calls this: LAUNCH,
     * a role switch, a newer check, the window closing.
     */
    private void cancelConnectionCheck(String reason) {
        checkGeneration++;
        if (checkTimer != null) {
            LOG.info("Stopping the connection check because " + reason);
            checkTimer.stop();
            checkTimer = null;
        }
    }

    /**
     * True when a finished connection check may still open the launcher's listener on the co-op
     * port. The check runs for up to twenty seconds and nothing disables LAUNCH while it does, so
     * by the time the router answers the port can belong to the game - and a listener bound then
     * either fails the game's own bind or answers the guest with a launcher banner.
     */
    static boolean checkResultStillApplies(int generation, int currentGeneration,
                                           boolean gameRunning, boolean hostSelected) {
        return generation == currentGeneration && !gameRunning && hostSelected;
    }

    /**
     * Why a host connection check must not start, or {@code null} when it may. The check maps the
     * co-op port and then releases the mapping, which is the same mapping a running game holds: run
     * mid-session it would delete the forward the guest's traffic is coming through.
     */
    static String connectionCheckBlockedReason(boolean gameRunning) {
        return gameRunning
                ? "Starsector is running and owns the port. Checking the connection now would delete"
                        + " the router mapping the session is using. Close the game first."
                : null;
    }

    /**
     * True when a finished public-address lookup may write its answer into the field. An automatic
     * lookup takes up to ten seconds, and the player types a LAN or VPN address into that field
     * while it runs; overwriting it changed the invite under a host who had already copied one.
     */
    static boolean shouldApplyLookedUpAddress(boolean automatic, String textWhenStarted,
                                              String textNow) {
        if (!automatic) {
            return true;
        }
        String now = textNow == null ? "" : textNow.trim();
        String before = textWhenStarted == null ? "" : textWhenStarted.trim();
        return now.isEmpty() || now.equals(before);
    }

    private void closeListener(String reason) {
        if (listener == null) {
            return;
        }
        LOG.info("Closing the launcher listener because " + reason);
        listener.close();
        listener = null;
    }

    private void append(String line) {
        if (statusArea == null) {
            return;
        }
        String stamped = LocalTime.now().format(CLOCK) + "  " + line;
        statusArea.append(stamped + "\n");
        statusArea.setCaretPosition(statusArea.getDocument().getLength());
    }

    private void fail(String message) {
        append(message.replace("\n\n", " ").replace("\n", " "));
        JOptionPane.showMessageDialog(frame, message, "Starsector Coop", JOptionPane.WARNING_MESSAGE);
    }

    private Integer parsePort(String text, String what) {
        String trimmed = text == null ? "" : text.trim();
        int port;
        try {
            port = Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            fail("The " + what + " has to be a number between 1 and 65535, not \"" + trimmed + "\".");
            return null;
        }
        if (port < 1 || port > 65535) {
            fail("The " + what + " has to be between 1 and 65535.");
            return null;
        }
        return port;
    }

    private static String password(JPasswordField field) {
        char[] value = field.getPassword();
        return value == null ? "" : new String(value);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String tierName(CoopPortMapper.Tier tier) {
        return switch (tier) {
            case UPNP -> "UPnP";
            case NAT_PMP -> "NAT-PMP";
            case PCP -> "PCP";
            case NONE -> "no";
        };
    }

    private static JComboBox<String> combo(List<String> allowed, String defaultValue) {
        JComboBox<String> box = new JComboBox<>(allowed.toArray(new String[0]));
        select(box, defaultValue, defaultValue);
        return box;
    }

    /** Selects {@code value} when it is one of the entries, else {@code fallback}. */
    private static void select(JComboBox<String> box, String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        for (int i = 0; i < box.getItemCount(); i++) {
            if (!trimmed.isEmpty() && trimmed.equalsIgnoreCase(box.getItemAt(i))) {
                box.setSelectedIndex(i);
                return;
            }
        }
        for (int i = 0; i < box.getItemCount(); i++) {
            if (fallback.equalsIgnoreCase(box.getItemAt(i))) {
                box.setSelectedIndex(i);
                return;
            }
        }
        if (box.getItemCount() > 0) {
            box.setSelectedIndex(0);
        }
    }

    private static String selected(JComboBox<String> box) {
        Object value = box.getSelectedItem();
        return value == null ? "" : String.valueOf(value);
    }

    private static String registryDefault(String key) {
        return CoopOptionsRegistry.require(key).defaultValue();
    }

    private static String orDefault(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /** An integer spinner over a registry key's range, starting at its default. */
    private static JSpinner spinner(String key, int step) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        int min = option.min() == Integer.MIN_VALUE ? 0 : option.min();
        int max = option.max() == Integer.MAX_VALUE ? 65535 : option.max();
        int start = Integer.parseInt(option.defaultValue());
        return new JSpinner(new SpinnerNumberModel(start, Math.min(min, start), Math.max(max, start), step));
    }

    private static JCheckBox flag(String text, String tooltip) {
        JCheckBox box = new JCheckBox(text);
        box.setOpaque(false);
        box.setForeground(CoopTheme.TEXT);
        box.setToolTipText(tooltip);
        return box;
    }

    private void setSpinner(JSpinner spinner, String key) {
        String value = config.value(key).trim();
        if (value.isEmpty()) {
            spinner.setValue(Integer.parseInt(registryDefault(key)));
            return;
        }
        try {
            SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
            int parsed = Integer.parseInt(value);
            int min = ((Number) model.getMinimum()).intValue();
            int max = ((Number) model.getMaximum()).intValue();
            spinner.setValue(Math.max(min, Math.min(max, parsed)));
        } catch (NumberFormatException ex) {
            LOG.warn("Ignoring an unreadable " + key + " in the settings file: " + value);
            spinner.setValue(Integer.parseInt(registryDefault(key)));
        }
    }

    private void setFlag(JCheckBox box, String key) {
        String value = config.value(key).trim();
        box.setSelected(value.isEmpty() ? Boolean.parseBoolean(registryDefault(key))
                : Boolean.parseBoolean(value));
    }

    /** The spinner's value as text, or blank when it equals the registry default. */
    private static String nonDefault(JSpinner spinner, String key) {
        String value = String.valueOf(spinner.getValue());
        return value.equals(registryDefault(key)) ? "" : value;
    }

    /** The checkbox as {@code true}/{@code false}, or blank when it equals the registry default. */
    private static String nonDefault(JCheckBox box, String key) {
        String value = String.valueOf(box.isSelected());
        return value.equals(registryDefault(key)) ? "" : value;
    }

    private void setClipboard(String text) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
        } catch (Exception ex) {
            LOG.warn("Could not write to the clipboard", ex);
            append("Could not write to the clipboard; copy the line above by hand.");
        }
    }

    private String readClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            return data == null ? "" : String.valueOf(data);
        } catch (Exception ex) {
            LOG.warn("Could not read the clipboard", ex);
            return "";
        }
    }

    private void openPath(File path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(path);
                    return;
                }
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(path.toURI());
                    return;
                }
            }
            append("This system will not open " + path + " for me. Open it by hand.");
        } catch (Exception ex) {
            LOG.warn("Could not open " + path, ex);
            append("Could not open " + path + ": " + ex.getMessage());
        }
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
                return;
            }
            setClipboard(url);
            append("This system will not open a browser for me. The address is on your clipboard: "
                    + url);
        } catch (Exception ex) {
            LOG.warn("Could not open " + url, ex);
            append("Could not open " + url + ": " + ex.getMessage());
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
