package coop.launcher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JDialog;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JTabbedPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
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
 * mod itself cannot reach - the {@code coop-forks.jar} classpath entry in {@code vmparams} and the
 * mod tick in {@code enabled_mods.json} - get a <b>Fix</b> button on their install row that applies
 * the edit ({@link CoopInstallFixer}), with the manual instructions kept on the row and in
 * {@code INSTALL.md} for the installs where the write is refused.
 *
 * <p>Engine-free by construction: this source set is compiled without {@code starfarer.api.jar} on
 * the classpath. The mod classes it does reuse ({@link CoopPortMapper},
 * {@link CoopConnectionDoctor}, {@link CoopOptionsRegistry}) do not link to the game API.
 *
 * <p>The compact setup screen keeps the role switch and launch bar in place. Campaign,
 * connection, installation and settings details live in owned windows; the modeless log window
 * can stay open during play without resizing setup.
 */
public final class CoopLauncherApp {

    private static final Logger LOG = Logger.getLogger(CoopLauncherApp.class);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * The adopt-campaign checkbox's label. Public and named because the mod quotes it back at the
     * player: the seed-lock desync dialog tells a guest whose save is from another campaign to tick
     * this exact box, and advice that names a control the player cannot find is worse than none.
     *
     * <p>The mod cannot import this class (the launcher source set is compiled without the game API,
     * so the dependency only goes one way), so {@code coop.ui.CoopDesyncDialog} keeps its own copy and
     * {@code CoopLauncherAppTest} fails if the two ever drift.
     */
    public static final String ADOPT_CAMPAIGN_LABEL = "Start over inside the host's campaign (guest)";

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
    private JButton updateChip;

    // session card
    private JPanel sessionCard;
    private JPanel sessionBody;
    private JPanel hostForm;
    private JPanel guestForm;
    private JTextField hostPortField;
    private JPasswordField hostPasswordField;
    private JTextField hostSeedField;
    private JButton hostSeedGenerateButton;
    private JTextField publicAddressField;
    private JTextField invitePreviewField;
    private JButton copyInviteButton;
    private JComboBox<String> sectorSizeBox;
    private JComboBox<String> sectorAgeBox;
    private JComboBox<CoopCampaignPicker.Entry> campaignBox;
    private JLabel campaignFolderLabel;
    private JTextArea hostSaveHint;

    private JTextField guestInviteField;
    private JTextArea guestInviteNote;
    private JTextArea guestSaveHint;
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
    private JTextField markKeyField;
    private JCheckBox diagnosticsBox;
    private JCheckBox wiretapBox;
    private JSpinner wiretapSampleSpinner;
    private JCheckBox frameProfileBox;
    private JCheckBox bridgeEnabledBox;
    private JSpinner bridgePortSpinner;
    private JSpinner interactionDelaySpinner;
    private JCheckBox fullFidelityBox;
    private JCheckBox ffDisableBox;
    private JCheckBox clockDisableBox;
    private JCheckBox allowGameVersionMismatchBox;
    private JCheckBox adoptCampaignBox;

    // install card
    private CoopTheme.StatusLabel installSummary;
    private JButton connectionDetailsButton;
    private JPanel rowsPanel;
    private JButton showAllButton;
    private boolean showAllRows;

    // footer + drawer
    private JButton launchButton;
    private JTextArea footerHint;
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
    private JDialog logDialog;
    private JDialog settingsDialog;
    private JDialog installDialog;
    private JDialog worldDialog;
    private JDialog hostNetworkDialog;
    private JDialog guestNetworkDialog;
    private JDialog connectionDialog;
    private JButton customizeButton;
    private JTextArea worldSummary;
    private JLabel hostEndpoint;
    private JTextArea inviteFeedback;
    private JTextArea guestSummary;
    private JTextArea hostSaveDetails;
    private JTextArea connectionSummary;
    private JLabel connectionTitle;
    private JButton publicLookupButton;
    private String copiedInvite = "";
    private boolean checkingConnection;
    private boolean connectionChecked;
    private boolean addressLookupRunning;
    private CoopPortMapper connectionMapper;
    private boolean mappingCleanupPending;
    private boolean updatingConnectionAddress;
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
    /** True while the code, not the player, is repopulating the campaign picker. */
    private boolean writingCampaignBox;
    /** True while the code, not the player, is writing the host seed field. */
    private boolean writingSeedField;
    /** True while the code, not the player, is moving the sector size and star age drop-downs. */
    private boolean writingWorldBoxes;
    /**
     * The seed for a new campaign, kept aside while a saved campaign is selected. The box itself
     * shows the picked campaign's own seed then, so this is the only copy of what the host had
     * drafted, and going back to New puts it back.
     */
    private String draftSeed = "";
    /**
     * The same for the two world drop-downs, which follow the picker for the reason the seed does: a
     * saved campaign shows the settings its own sector was generated at, and New puts the host's
     * draft back.
     */
    private String draftSectorSize = DEFAULT_SECTOR_SIZE;
    private String draftSectorAge = DEFAULT_STAR_AGE;
    /** The campaign the picker was last on, so a pick can be told from a redraw of the same pick. */
    private String pickedCampaignId = CoopCampaignPicker.NEW_CAMPAIGN_ID;

    /**
     * The co-op save list as of the last read. Never null: an install with no list reads as
     * {@link CoopSaveIndexReader.Status#ABSENT}, which is what a first session looks like and not
     * something to complain about.
     */
    private CoopSaveIndexReader.Index saveIndex = CoopSaveIndexReader.Index.absent();
    /**
     * Bumped by every save-index read, so an older one landing late cannot overwrite a newer one.
     * Reads happen on window focus, which can outrun itself when somebody alt-tabs twice.
     */
    private int saveIndexGeneration;
    /** The campaign the pasted invite names, {@code ""} for a new campaign or no invite. */
    private String invitedCampaignId = "";
    /** True once an invite has parsed, which is when the guest's save hint has anything to say. */
    private boolean guestInviteAccepted;

    private CoopLauncherProbe.HostListener listener;
    private CoopLogTail logTail;
    private Process gameProcess;
    private javax.swing.Timer checkTimer;

    /**
     * Command-line flag the elevated copy of the launcher is started with. It means "apply the
     * install fixes as soon as the window is up, then carry on as a normal launcher": the
     * unelevated copy that asked for administrator rights has already exited, so there is nobody
     * left to press the button a second time.
     */
    static final String APPLY_FIX_FLAG = "--apply-install-fix";

    public static void main(String[] args) {
        CoopInstallLayout discovered = CoopInstallLayout.discover();
        File logFile = discovered == null
                ? new File("coop-launcher.log")
                : discovered.launcherLog();
        CoopLauncherLogging.configure(logFile);
        boolean applyFix = List.of(args).contains(APPLY_FIX_FLAG);
        LOG.info("Coop launcher starting; layout " + (discovered == null ? "not found" : discovered)
                + (applyFix ? "; asked to apply the install fixes" : ""));
        SwingUtilities.invokeLater(() -> new CoopLauncherApp().start(discovered, applyFix));
    }

    /**
     * True in the copy started by {@link #offerElevatedRelaunch}. It stops that copy from asking for
     * administrator rights again when the write fails for some reason other than permissions.
     */
    private boolean alreadyElevated;

    private void start(CoopInstallLayout discovered, boolean applyFix) {
        this.alreadyElevated = applyFix;
        CoopTheme.install();
        this.layout = discovered;
        this.launcherVersion = CoopInstallCheck.launcherVersion();
        buildFrame();
        if (layout == null) {
            append("This folder does not look like a Starsector install, so the launcher could not"
                    + " work out where the game is. Use \"Folder\" in Installation details to point at it.");
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
        if (applyFix && layout != null) {
            // Started with administrator rights by an unelevated copy that could not write the
            // files. Both targets are safe to run: the fixer writes nothing when a file is already
            // right, so the one that was not broken reports "nothing to change".
            applyInstallFix(List.of(CoopInstallFixer.Target.VMPARAMS,
                    CoopInstallFixer.Target.ENABLED_MODS), "the elevated relaunch");
        }
        if (System.getenv("COOP_LAUNCHER_PREVIEW") == null) {
            startUpdateCheck();
        }
        if (hostSegment.isSelected() && publicAddressField.getText().trim().isEmpty()
                && System.getenv("COOP_LAUNCHER_PREVIEW") == null) {
            lookUpPublicAddress(null, true);
        }
        applyPreview();
        frame.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            if (hostSegment.isSelected()) {
                campaignBox.requestFocusInWindow();
            } else {
                guestInviteField.requestFocusInWindow();
            }
        });
    }

    /**
     * Dev only: {@code COOP_LAUNCHER_PREVIEW=host|guest} stages the window for a screenshot (role
     * selected, sample connection results). Append "-logs" to open the log window. No check runs.
     */
    private void applyPreview() {
        String preview = System.getenv("COOP_LAUNCHER_PREVIEW");
        if (preview == null || preview.isBlank()) {
            return;
        }
        LOG.info("Preview mode " + preview);
        boolean guest = preview.trim().toLowerCase(java.util.Locale.ROOT).startsWith("guest");
        boolean install = preview.trim().equalsIgnoreCase("install");
        hostSegment.setSelected(!guest);
        guestSegment.setSelected(guest);
        onRoleChanged();
        if (guest) {
            writingGuestInvite = true;
            guestInviteField.setText("coop://203.0.113.9:7777/?seed=MN-8402913377120455081&pw=k7mxq2rp4d&size=normal&age=mixed");
            writingGuestInvite = false;
            applyInviteText(guestInviteField.getText(), false);
            guestInviteField.setCaretPosition(0);
            setChips(List.of(new Chip("TCP passed", CoopTheme.OK), new Chip("launcher 0.1.2", CoopTheme.OK),
                    new Chip("UDP passed", CoopTheme.OK), new Chip("3 ms", CoopTheme.OK)));
            note("The host's launcher answered on TCP and UDP. Press Launch when your host does.");
        } else {
            hostSeedField.setText("MN-8402913377120455081");
            publicAddressField.setText("203.0.113.9");
            setChips(List.of(new Chip("UPnP mapped", CoopTheme.OK), new Chip("203.0.113.9:7777", CoopTheme.OK),
                    new Chip("listening on 7777", CoopTheme.OK)));
            note("Your router opened 203.0.113.9:7777. Copy the invite and ask your partner to press"
                    + " Check connection. The full doctor block is in the log.");
        }
        setConnectionStatus(guest ? "Host reachable" : "Router prepared", CoopIcons.Symbol.CHECK, CoopTheme.OK);
        connectionSummary.setText(guest ? "TCP passed · UDP passed · 3 ms. Launch when your host does."
                : "Copy the invite, then ask your partner to check the connection.");
        connectionChecked = true;
        updateLaunchGate();
        if (install) {
            showAllRows = true;
            renderRows();
            SwingUtilities.invokeLater(() -> showPanel(installDialog));
        } else if (preview.contains("logs")) {
            SwingUtilities.invokeLater(() -> setDrawerVisible(true));
        }
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
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent event) {
                refreshSaveIndex("the launcher window came back to the front");
            }
        });

        JPanel root = CoopLauncherUi.panel();
        root.setOpaque(true);
        root.setBackground(CoopTheme.BG);
        root.add(buildHeader(), BorderLayout.NORTH);

        JPanel setup = CoopLauncherUi.panel();
        setup.setBorder(BorderFactory.createEmptyBorder(6, 22, 12, 22));
        sessionCard = buildSessionCard();
        setup.add(sessionCard, BorderLayout.CENTER);
        setup.add(buildConnectionCard(), BorderLayout.SOUTH);
        JScrollPane setupScroll = CoopLauncherUi.scroll(setup);
        setupScroll.setName("setupScroll");
        root.add(setupScroll, BorderLayout.CENTER);

        advancedCard = buildAdvancedCard();
        settingsDialog = CoopLauncherUi.dialog(frame, "Settings",
                CoopLauncherUi.scroll(advancedCard), 660, 520, true,
                "Changes are used when you launch.");
        installDialog = CoopLauncherUi.dialog(frame, "Installation",
                CoopLauncherUi.scroll(buildInstallCard()), 720, 500, false, "");
        drawer = buildDrawer();
        logDialog = CoopLauncherUi.dialog(frame, "Session log", drawer, 760, 460, false,
                "This window can stay open while you play.");
        root.add(buildFooter(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(
                Math.min(com.formdev.flatlaf.util.UIScale.scale(620), screen.width - 24),
                Math.min(com.formdev.flatlaf.util.UIScale.scale(480), screen.height - 24)));
        // Size once from both role layouts and the active font scale. Keep a little breathing
        // room without a fixed-height empty area; subsequent edits must not resize the window.
        frame.pack();
        int preferredHeight = frame.getHeight() + com.formdev.flatlaf.util.UIScale.scale(8);
        frame.setSize(Math.min(com.formdev.flatlaf.util.UIScale.scale(720), screen.width - 24),
                Math.min(preferredHeight, screen.height - 24));
        frame.setLocationRelativeTo(null);
    }

    private JComponent buildHeader() {
        JPanel header = CoopLauncherUi.panel();
        header.setBorder(BorderFactory.createEmptyBorder(16, 22, 0, 22));
        JLabel title = new JLabel("Starsector Coop");
        title.setName("launcherTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 8f));
        JPanel brand = new JPanel(new GridBagLayout());
        brand.setOpaque(false);
        GridBagConstraints brandLayout = new GridBagConstraints();
        brandLayout.anchor = GridBagConstraints.BASELINE;
        brand.add(title, brandLayout);
        JLabel version = CoopTheme.small(launcherVersion);
        version.setName("launcherVersion");
        brandLayout.insets = new Insets(0, 10, 0, 0);
        brand.add(version, brandLayout);
        updateChip = CoopTheme.ghost("Update available");
        updateChip.setVisible(false);
        updateChip.addActionListener(event -> {
            if (!updateUrl.isEmpty()) {
                openUrl(updateUrl);
            }
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        advancedToggle = CoopTheme.ghost("Settings");
        CoopIcons.apply(advancedToggle, CoopIcons.Symbol.SETTINGS);
        advancedToggle.addActionListener(event -> showPanel(settingsDialog));
        logToggle = CoopTheme.ghost("Logs");
        CoopIcons.apply(logToggle, CoopIcons.Symbol.TERMINAL);
        logToggle.addActionListener(event -> setDrawerVisible(!logDialog.isVisible()));
        actions.add(updateChip);
        actions.add(advancedToggle);
        actions.add(logToggle);
        JPanel chrome = CoopLauncherUi.panel();
        chrome.add(brand, BorderLayout.WEST);
        chrome.add(CoopLauncherUi.centered(actions), BorderLayout.EAST);
        header.add(chrome, BorderLayout.NORTH);

        hostSegment = CoopTheme.segment("Host a game");
        guestSegment = CoopTheme.segment("Join a game");
        CoopIcons.apply(hostSegment, CoopIcons.Symbol.HOST);
        CoopIcons.apply(guestSegment, CoopIcons.Symbol.LINK);
        ButtonGroup group = new ButtonGroup();
        group.add(hostSegment);
        group.add(guestSegment);
        hostSegment.addActionListener(event -> onRoleChanged());
        guestSegment.addActionListener(event -> onRoleChanged());
        JPanel roles = new JPanel(new GridLayout(1, 2, 4, 0));
        roles.setBackground(CoopTheme.FIELD);
        roles.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CoopTheme.CARD_BORDER),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        roles.add(hostSegment);
        roles.add(guestSegment);
        JPanel roleRow = CoopLauncherUi.panel();
        roleRow.setBorder(BorderFactory.createEmptyBorder(10, 0, 8, 0));
        roleRow.add(roles);
        header.add(roleRow, BorderLayout.SOUTH);
        return header;
    }

    private JPanel buildSessionCard() {
        sessionBody = new JPanel(new CardLayout());
        sessionBody.setOpaque(false);
        hostForm = buildHostForm();
        guestForm = buildGuestForm();
        sessionBody.add(hostForm, "host");
        sessionBody.add(guestForm, "guest");
        return sessionBody;
    }

    private JPanel buildHostForm() {
        JPanel panel = CoopLauncherUi.panel();
        Form form = new Form(panel);
        campaignBox = new JComboBox<>();
        campaignBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                           int index, boolean selected, boolean focus) {
                Object label = value instanceof CoopCampaignPicker.Entry entry && entry.newCampaign()
                        ? "New campaign" : value;
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        });
        campaignBox.addItem(CoopCampaignPicker.newCampaignEntry(""));
        CoopTheme.inputHeight(campaignBox);
        campaignBox.addActionListener(event -> {
            if (!writingCampaignBox) {
                onCampaignPicked();
            }
        });
        customizeButton = CoopTheme.ghost("Customize");
        CoopLauncherUi.stableWidth(customizeButton, "Customize", "Details");
        customizeButton.addActionListener(event -> showPanel(worldDialog));
        form.full("Campaign", CoopLauncherUi.beside(campaignBox, customizeButton));
        worldSummary = CoopLauncherUi.summary(1);
        form.raw(worldSummary);

        JPanel world = CoopLauncherUi.panel();
        Form worldForm = new Form(world);
        hostSeedField = CoopTheme.textField("Generate a seed");
        hostSeedGenerateButton = CoopTheme.secondary("Generate seed");
        hostSeedGenerateButton.addActionListener(event -> {
            String seed = CoopSeeds.generate();
            draftSeed = seed;
            hostSeedField.setText(seed);
            append("New seed " + seed + ". Copy a fresh invite for your partner.");
        });
        worldForm.full("Sector seed", CoopLauncherUi.beside(hostSeedField, hostSeedGenerateButton));
        sectorSizeBox = combo(SECTOR_SIZES, DEFAULT_SECTOR_SIZE);
        sectorAgeBox = combo(STAR_AGES, DEFAULT_STAR_AGE);
        worldForm.pair("Sector size", sectorSizeBox, "Star age", sectorAgeBox);
        campaignFolderLabel = CoopTheme.muted("");
        worldForm.raw(campaignFolderLabel);
        hostSaveDetails = CoopTheme.paragraph("");
        worldForm.raw(hostSaveDetails);
        worldDialog = CoopLauncherUi.dialog(frame, "Campaign settings", CoopLauncherUi.scroll(world),
                650, 340, true, "Changes are used when you launch.");

        JPanel network = CoopLauncherUi.panel();
        Form networkForm = new Form(network);
        hostPortField = CoopTheme.textField(String.valueOf(DEFAULT_PORT));
        hostPortField.setText(String.valueOf(DEFAULT_PORT));
        hostPasswordField = CoopTheme.passwordField("No password");
        hostPasswordField.setToolTipText("Generated for you and included in the invite. Clear for an open session.");
        hostPasswordField.getDocument().addDocumentListener(onAnyEdit(this::noticeHostPasswordCleared));
        publicAddressField = CoopTheme.textField("Public, LAN or VPN address");
        publicLookupButton = CoopTheme.secondary("Look up");
        CoopLauncherUi.stableWidth(publicLookupButton, "Looking up…");
        publicLookupButton.addActionListener(event -> lookUpPublicAddress(null));
        networkForm.full("Address your partner connects to",
                CoopLauncherUi.beside(publicAddressField, publicLookupButton));
        networkForm.pair("Port", hostPortField, "Password", hostPasswordField);
        invitePreviewField = CoopTheme.textField("An address and valid seed are needed");
        invitePreviewField.setEditable(false);
        networkForm.full("Full invite", invitePreviewField);
        networkForm.raw(CoopTheme.paragraph("Use a LAN or VPN address when that is how you connect."
                + " The invite carries the address, password and campaign settings."));
        hostNetworkDialog = CoopLauncherUi.dialog(frame, "Connection settings",
                CoopLauncherUi.scroll(network), 650, 360, true, "Changes are used when you launch.");

        Card inviteCard = new Card("Invite your partner", true);
        inviteCard.setName("inviteCard");
        inviteCard.remove(inviteCard.header);
        JButton edit = CoopLauncherUi.iconButton("Edit connection", CoopIcons.Symbol.EDIT);
        edit.setName("editHostConnection");
        edit.addActionListener(event -> showPanel(hostNetworkDialog));
        hostEndpoint = CoopTheme.muted("");
        hostEndpoint.putClientProperty("html.disable", true);
        hostEndpoint.setMinimumSize(new Dimension(0, hostEndpoint.getPreferredSize().height));
        copyInviteButton = CoopTheme.secondary("Copy invite");
        CoopIcons.apply(copyInviteButton, CoopIcons.Symbol.COPY);
        CoopLauncherUi.stableWidth(copyInviteButton, "Copy invite", "Copied", "Looking up…");
        copyInviteButton.addActionListener(event -> copyInvite());
        inviteFeedback = CoopLauncherUi.summary(1);
        JPanel inviteText = new JPanel(new BorderLayout(0, 5));
        inviteText.setOpaque(false);
        inviteText.add(inviteCard.titleLabel, BorderLayout.NORTH);
        inviteText.add(hostEndpoint, BorderLayout.CENTER);
        inviteText.add(inviteFeedback, BorderLayout.SOUTH);
        JPanel inviteActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        inviteActions.setOpaque(false);
        inviteActions.add(edit);
        inviteActions.add(copyInviteButton);
        new Form(inviteCard.body).raw(CoopLauncherUi.beside(inviteText,
                CoopLauncherUi.centered(inviteActions)));
        form.raw(inviteCard);
        hostSaveHint = CoopLauncherUi.summary(2);
        hostSaveHint.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        form.raw(hostSaveHint);

        DocumentListener preview = onAnyEdit(this::refreshInvitePreview);
        hostPortField.getDocument().addDocumentListener(preview);
        hostPasswordField.getDocument().addDocumentListener(preview);
        hostSeedField.getDocument().addDocumentListener(preview);
        hostSeedField.getDocument().addDocumentListener(onAnyEdit(this::onSeedFieldEdited));
        publicAddressField.getDocument().addDocumentListener(preview);
        hostPortField.getDocument().addDocumentListener(onAnyEdit(this::connectionInputsChanged));
        publicAddressField.getDocument().addDocumentListener(onAnyEdit(this::connectionInputsChanged));
        sectorSizeBox.addActionListener(event -> onWorldBoxEdited());
        sectorAgeBox.addActionListener(event -> onWorldBoxEdited());
        return panel;
    }

    private JPanel buildGuestForm() {
        JPanel panel = CoopLauncherUi.panel();
        Form form = new Form(panel);
        guestInviteField = CoopTheme.textField("Paste the coop:// invite from your host");
        JButton paste = CoopTheme.secondary("Paste invite");
        CoopIcons.apply(paste, CoopIcons.Symbol.PASTE);
        paste.addActionListener(event -> pasteInvite());
        guestInviteField.getDocument().addDocumentListener(onAnyEdit(this::onGuestInviteTyped));
        form.full("Invite from your host", CoopLauncherUi.beside(guestInviteField, paste));
        guestInviteNote = CoopLauncherUi.summary(2);
        guestInviteNote.setText("Paste an invite, or enter the connection manually.");
        form.raw(guestInviteNote);

        JPanel manual = CoopLauncherUi.panel();
        Form manualForm = new Form(manual);
        guestHostField = CoopTheme.textField("Name, IPv4 or IPv6");
        guestPortField = CoopTheme.textField(String.valueOf(DEFAULT_PORT));
        guestPortField.setText(String.valueOf(DEFAULT_PORT));
        manualForm.pair("Host address", guestHostField, "Port", guestPortField);
        guestPasswordField = CoopTheme.passwordField("None");
        guestSeedField = CoopTheme.textField("From the invite or your host");
        manualForm.pair("Password", guestPasswordField, "Sector seed", guestSeedField);
        guestSectorSizeField = CoopTheme.textField("");
        guestSectorAgeField = CoopTheme.textField("");
        guestSectorSizeField.setText(DEFAULT_SECTOR_SIZE);
        guestSectorAgeField.setText(DEFAULT_STAR_AGE);
        guestSectorSizeField.setEditable(false);
        guestSectorAgeField.setEditable(false);
        manualForm.pair("Sector size (from invite)", guestSectorSizeField,
                "Star age (from invite)", guestSectorAgeField);
        guestNetworkDialog = CoopLauncherUi.dialog(frame, "Host connection",
                CoopLauncherUi.scroll(manual), 630, 340, true, "Changes are used when you launch.");

        Card summary = new Card("Your session", true);
        JButton edit = CoopLauncherUi.iconButton("Edit connection", CoopIcons.Symbol.EDIT);
        edit.addActionListener(event -> showPanel(guestNetworkDialog));
        summary.trailing.add(edit);
        guestSummary = CoopLauncherUi.summary(2);
        new Form(summary.body).raw(guestSummary);
        form.raw(summary);
        guestSaveHint = CoopLauncherUi.summary(2);
        guestSaveHint.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        form.raw(guestSaveHint);
        DocumentListener gate = onAnyEdit(this::connectionInputsChanged);
        guestHostField.getDocument().addDocumentListener(gate);
        guestPortField.getDocument().addDocumentListener(gate);
        guestSeedField.getDocument().addDocumentListener(onAnyEdit(this::updateLaunchGate));
        return panel;
    }

    private JPanel buildConnectionCard() {
        JPanel card = CoopLauncherUi.panel();
        card.setName("connectionStatus");
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, CoopTheme.CARD_BORDER),
                BorderFactory.createEmptyBorder(15, 0, 0, 0)));
        connectionTitle = new JLabel("Connection not checked");
        connectionTitle.setFont(connectionTitle.getFont().deriveFont(Font.BOLD));
        connectionDetailsButton = CoopLauncherUi.iconButton("Connection details", CoopIcons.Symbol.MINUS);
        connectionDetailsButton.setIcon(CoopIcons.of(CoopIcons.Symbol.MINUS, 32));
        connectionDetailsButton.addActionListener(event -> showPanel(connectionDialog));
        connectionButton = CoopTheme.secondary("Check connection");
        CoopLauncherUi.stableWidth(connectionButton, "Check connection", "Check again", "Checking…");
        connectionButton.addActionListener(event -> {
            if (hostSegment.isSelected()) {
                checkMyConnection();
            } else {
                testConnection();
            }
        });
        connectionSummary = CoopTheme.paragraph("");
        connectionSummary.setFont(connectionSummary.getFont().deriveFont(
                connectionSummary.getFont().getSize() - 1f));
        JPanel text = CoopLauncherUi.statusBlock(connectionTitle, connectionSummary, 2);
        JPanel status = CoopLauncherUi.panel();
        status.add(CoopLauncherUi.centered(connectionDetailsButton), BorderLayout.WEST);
        status.add(text, BorderLayout.CENTER);
        card.add(status, BorderLayout.CENTER);
        card.add(CoopLauncherUi.centered(connectionButton), BorderLayout.EAST);
        chipRow = new JPanel(new CoopTheme.WrapLayout(6, 4));
        chipRow.setOpaque(false);
        connectionNote = CoopTheme.paragraph("");
        JPanel detail = CoopLauncherUi.panel();
        detail.add(chipRow, BorderLayout.NORTH);
        detail.add(connectionNote, BorderLayout.CENTER);
        connectionDialog = CoopLauncherUi.dialog(frame, "Connection details",
                CoopLauncherUi.scroll(detail), 640, 320, false, "");
        return card;
    }

    private void setConnectionStatus(String title, CoopIcons.Symbol symbol, java.awt.Color color) {
        connectionTitle.setText(title);
        connectionDetailsButton.setIcon(CoopIcons.of(symbol, 32));
        connectionDetailsButton.setForeground(color);
        connectionDetailsButton.getAccessibleContext().setAccessibleDescription(title);
    }

    private Card buildAdvancedCard() {
        Card card = new Card("Settings");

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        Form form = new Form(panel);

        portMappingBox = combo(CoopOptionsRegistry.require(CoopOptionsRegistry.PORT_MAPPING)
                .allowedValues(), registryDefault(CoopOptionsRegistry.PORT_MAPPING));
        portMappingBox.setToolTipText("auto asks your router to forward the port over UPnP. Host"
                + " only.");
        portMappingBox.addActionListener(event -> connectionInputsChanged());
        hudCornerBox = combo(CoopOptionsRegistry.require(CoopOptionsRegistry.HUD_CORNER)
                .allowedValues(), registryDefault(CoopOptionsRegistry.HUD_CORNER));
        hudCornerBox.setToolTipText("Where the one-line link status sits on screen. Local only.");
        form.pair("Port mapping", portMappingBox, "Link HUD corner", hudCornerBox);

        markKeyField = CoopTheme.textField(CoopOptionsRegistry.require(CoopOptionsRegistry.MARK_KEY)
                .defaultValue());
        markKeyField.setToolTipText("Writes a COOP-MARK line into both players' logs when you press"
                + " it, so a test session can be lined up afterwards. An LWJGL key name such as F11"
                + " or F9; blank uses F11.");
        form.full("Log marker key", markKeyField);

        reconnectGraceSpinner = spinner(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, 5);
        reconnectGraceSpinner.setToolTipText("How long a dropped link keeps the session alive."
                + " Host decides.");
        bridgeEnabledBox = flag("Agent bridge", "Opens a 127.0.0.1 socket that the dev tooling in"
                + " tools/starsector-mcp talks to. Off for normal play. With the port below at 0 it"
                + " uses 7801 when this launcher hosts and 7802 when it joins.");
        bridgeEnabledBox.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 0));
        bridgeEnabledBox.addActionListener(event ->
                bridgePortSpinner.setEnabled(bridgeEnabledBox.isSelected()));
        bridgePortSpinner = spinner(CoopOptionsRegistry.LAUNCHER_BRIDGE_PORT, 1);
        bridgePortSpinner.setToolTipText("Port for the localhost agent bridge. 0 means the port for"
                + " this role: 7801 hosting, 7802 joining.");
        form.full("Reconnect grace (seconds)", reconnectGraceSpinner);
        JPanel developer = CoopLauncherUi.panel();
        form = new Form(developer);
        form.raw(bridgeEnabledBox);
        form.full("Agent bridge port (0 = the port for this role)", bridgePortSpinner);

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
        adoptCampaignBox = flag(ADOPT_CAMPAIGN_LABEL, "Overrides the seed"
                + " lock and adopts the host's in-flight campaign id. Discards this guest's co-op"
                + " progress. Never remembered between launches.");
        form.pair(null, allowGameVersionMismatchBox, null, adoptCampaignBox);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", CoopLauncherUi.scroll(panel));
        tabs.addTab("Developer", CoopLauncherUi.scroll(developer));
        tabs.setPreferredSize(new Dimension(560, 320));
        card.body.add(tabs, c);
        return card;
    }

    private Card buildInstallCard() {
        Card card = new Card("Install");
        installSummary = new CoopTheme.StatusLabel("Checking installation", CoopTheme.INFO);
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

        // A wrapping detail is only as tall as the width it is given, so the rows are measured
        // after they have one; a BoxLayout here caps every row at a single line.
        rowsPanel = CoopLauncherUi.wrappingStack();

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
        JPanel footer = CoopLauncherUi.panel();
        footer.setOpaque(true);
        footer.setBackground(CoopTheme.CARD);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, CoopTheme.CARD_BORDER),
                BorderFactory.createEmptyBorder(8, 22, 10, 22)));
        footer.setName("launchFooter");
        JPanel install = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        install.setOpaque(false);
        install.setName("installStatusRow");
        install.add(installSummary);
        JButton details = CoopTheme.inline("Details");
        details.setMargin(new Insets(2, 6, 2, 6));
        details.setFont(installSummary.getFont());
        details.addActionListener(event -> showPanel(installDialog));
        install.add(details);
        footerHint = CoopTheme.paragraph("");
        footerHint.setFont(footerHint.getFont().deriveFont(footerHint.getFont().getSize() - 1f));
        JPanel status = CoopLauncherUi.statusBlock(install, footerHint, 2);
        status.setName("launchStatus");
        launchButton = CoopTheme.primary("Launch Starsector");
        CoopIcons.apply(launchButton, CoopIcons.Symbol.PLAY);
        CoopLauncherUi.stableWidth(launchButton, "Launch Starsector", "Game running");
        launchButton.addActionListener(event -> launch());
        footer.add(status, BorderLayout.CENTER);
        footer.add(CoopLauncherUi.centered(launchButton), BorderLayout.EAST);
        return footer;
    }

    private JPanel buildDrawer() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CoopTheme.FIELD);
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, CoopTheme.CARD_BORDER));

        JPanel toolbar = new JPanel(new CoopTheme.WrapLayout(6, 6));
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
        ((javax.swing.text.DefaultCaret) statusArea.getCaret())
                .setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
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
        if (visible) {
            showPanel(logDialog);
        } else {
            logDialog.setVisible(false);
        }
    }

    private void showPanel(JDialog dialog) {
        if (dialog == null) {
            return;
        }
        if (!Boolean.TRUE.equals(dialog.getRootPane().getClientProperty("positioned"))) {
            dialog.setLocationRelativeTo(frame);
            dialog.getRootPane().putClientProperty("positioned", true);
        }
        if (dialog.isVisible()) {
            dialog.toFront();
        } else {
            dialog.setVisible(true);
        }
    }

    // ---- state ----------------------------------------------------------------------------------

    private void adoptLayout(CoopInstallLayout newLayout) {
        this.layout = newLayout;
        CoopLauncherLogging.configure(newLayout.launcherLog());
        LOG.info("Using install " + newLayout);
        append("Install: " + newLayout.installRoot());
        if (System.getenv("COOP_LAUNCHER_PREVIEW") == null) {
            clearAdoptConsent(newLayout, "a previous launch left it behind");
        }
        this.config = CoopLauncherConfig.read(newLayout.coopOptions());
        if (config.readError() != null) {
            LOG.warn("Settings file unreadable: " + config.readError());
        } else {
            LOG.info("Settings file " + (config.fileExisted() ? "read, keys " + config.keys()
                    : "not present yet"));
        }
        prefill();
        refreshInstallRows();
        refreshSaveIndex("the install was adopted");
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
        draftSeed = seed.trim();
        hostSeedField.setText(seed);
        guestSeedField.setText(seed);

        select(portMappingBox, config.value(CoopLauncherConfig.PORT_MAPPING),
                registryDefault(CoopOptionsRegistry.PORT_MAPPING));
        select(hudCornerBox, config.value(CoopLauncherConfig.HUD_CORNER),
                registryDefault(CoopOptionsRegistry.HUD_CORNER));
        markKeyField.setText(orDefault(config.value(CoopLauncherConfig.MARK_KEY),
                registryDefault(CoopOptionsRegistry.MARK_KEY)));
        select(sectorSizeBox, config.value(CoopLauncherConfig.SECTOR_SIZE), DEFAULT_SECTOR_SIZE);
        select(sectorAgeBox, config.value(CoopLauncherConfig.SECTOR_AGE), DEFAULT_STAR_AGE);
        draftSectorSize = selected(sectorSizeBox);
        draftSectorAge = selected(sectorAgeBox);
        guestSectorSizeField.setText(orDefault(config.value(CoopLauncherConfig.SECTOR_SIZE),
                DEFAULT_SECTOR_SIZE));
        guestSectorAgeField.setText(orDefault(config.value(CoopLauncherConfig.SECTOR_AGE),
                DEFAULT_STAR_AGE));

        setSpinner(reconnectGraceSpinner, CoopLauncherConfig.RECONNECT_GRACE_SECONDS);
        setFlag(bridgeEnabledBox, CoopLauncherConfig.LAUNCHER_BRIDGE_ENABLED);
        setSpinner(bridgePortSpinner, CoopLauncherConfig.LAUNCHER_BRIDGE_PORT);
        bridgePortSpinner.setEnabled(bridgeEnabledBox.isSelected());
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
        refreshCampaignEntries();
        onRoleChanged();
    }


    private void onRoleChanged() {
        boolean host = hostSegment.isSelected();
        ((CardLayout) sessionBody.getLayout()).show(sessionBody, host ? "host" : "guest");
        connectionInputsChanged();
        if (host) {
            maybeGenerateHostPassword();
            maybeGenerateHostSeed();
            refreshInvitePreview();
        }
        updateLaunchGate();
        sessionBody.revalidate();
        sessionBody.repaint();
    }

    /** An old result must never describe a different endpoint or a different role. */
    private void connectionInputsChanged() {
        if (connectionButton == null || updatingConnectionAddress) {
            return;
        }
        cancelConnectionCheck("the connection settings changed");
        closeListener("the connection settings changed");
        checkingConnection = false;
        connectionChecked = false;
        setChips(List.of());
        setConnectionStatus("Connection not checked", CoopIcons.Symbol.MINUS, CoopTheme.MUTED);
        note(hostSegment.isSelected() ? "Check before your partner tries to join."
                : "Test the host's launcher before starting the game.");
        updateLaunchGate();
    }

    private void refreshGuestSummary() {
        if (guestSummary == null) {
            return;
        }
        String address = guestHostField.getText().trim();
        guestSummary.setText(address.isEmpty() ? "Your host's campaign and connection will appear here."
                : CoopLauncherUi.brief(address + ":" + guestPortField.getText().trim() + " · "
                        + guestSectorSizeField.getText() + " sector · " + guestSectorAgeField.getText()
                        + " stars", 140));
    }

    // ---- which save to load ---------------------------------------------------------------------

    /**
     * Re-reads {@code saves/common/coop_saves.json.data} and the save folders it names, then
     * redraws the picker and both hint lines.
     *
     * <p>Off the event dispatch thread even though it is one small file and a stat per row: this
     * runs whenever the window comes back to the front, and a save folder on a slow or sleeping
     * drive would freeze the window for as long as the disk took. Same generation-counter shape as
     * the connection check - an alt-tab can start a second read before the first has landed, and
     * the older answer must not win.
     */
    private void refreshSaveIndex(String reason) {
        if (layout == null) {
            return;
        }
        java.nio.file.Path savesRoot = layout.saves().toPath();
        int generation = ++saveIndexGeneration;
        try {
            background.submit(() -> {
                CoopSaveIndexReader.Index read = CoopSaveIndexReader.read(savesRoot);
                SwingUtilities.invokeLater(() -> {
                    if (generation != saveIndexGeneration) {
                        return;
                    }
                    saveIndex = read;
                    LOG.info("Co-op save list read because " + reason + ": " + read.status() + ", "
                            + read.saves().size() + " save(s)"
                            + (read.problem().isEmpty() ? "" : "; " + read.problem()));
                    if (read.status() == CoopSaveIndexReader.Status.UNREADABLE
                            || read.status() == CoopSaveIndexReader.Status.TOO_NEW) {
                        append("The co-op save list (" + CoopSaveIndexReader.INDEX_DISPLAY_PATH
                                + ") could not be used: " + read.problem() + ". Nothing else is"
                                + " affected; you can still launch.");
                    }
                    refreshCampaignEntries();
                    refreshGuestSaveHint();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // The window is closing and the worker is gone. Nothing left to draw the answer on.
            LOG.info("Not reading the co-op save list; the launcher is shutting down");
        }
    }

    /**
     * Rebuilds the campaign drop-down from the current seed and save list, keeping whatever the
     * player had picked. A campaign whose last save has just been pruned falls back to New rather
     * than leaving a dead entry selected.
     */
    private void refreshCampaignEntries() {
        if (campaignBox == null || hostSaveHint == null) {
            return;
        }
        String wanted = selectedCampaignId();
        // The draft, not the field: while a saved campaign is picked the field shows that
        // campaign's seed, and the New entry has to keep quoting the seed New would use.
        List<CoopCampaignPicker.Entry> entries = CoopCampaignPicker.entries(
                draftSeed, saveIndex, ZoneId.systemDefault());
        writingCampaignBox = true;
        try {
            campaignBox.removeAllItems();
            for (CoopCampaignPicker.Entry entry : entries) {
                campaignBox.addItem(entry);
            }
            campaignBox.setSelectedItem(CoopCampaignPicker.select(entries, wanted));
        } finally {
            writingCampaignBox = false;
        }
        onCampaignPicked();
    }

    /**
     * The picker changed: the seed and world settings only mean something for a new campaign, the
     * folder line names the save, and the invite has to carry the campaign id from now on.
     */
    private void onCampaignPicked() {
        CoopCampaignPicker.Entry entry = selectedCampaignEntry();
        applyPickedWorldSettings(entry);
        boolean newCampaign = CoopCampaignPicker.worldControlsEnabled(entry);
        hostSeedField.setEnabled(newCampaign);
        hostSeedGenerateButton.setEnabled(newCampaign);
        customizeButton.setText(newCampaign ? "Customize" : "Details");
        sectorSizeBox.setEnabled(newCampaign);
        sectorAgeBox.setEnabled(newCampaign);
        String folder = CoopCampaignPicker.folderLine(entry);
        campaignFolderLabel.setText(folder);
        campaignFolderLabel.setVisible(!folder.isEmpty());
        setHint(hostSaveHint, CoopCampaignPicker.hint(
                entry == null ? "" : entry.campaignId(), saveIndex, ZoneId.systemDefault()), true);
        hostSaveDetails.setText(hostSaveHint.getToolTipText());
        refreshInvitePreview();
    }

    /**
     * The seed box and the two world drop-downs follow the picker: a saved campaign puts its own
     * seed, sector size and star age there, New puts the drafts back. Only on an actual change of
     * pick - a redraw of the same pick must not fight the controls.
     *
     * <p>A campaign saved before the mod recorded a value has none, and that control is then left
     * where the host had it; the hint line under the picker says so.
     *
     * <p>The write is queued rather than done here because this method is reachable from the seed
     * field's own document listener (a keystroke rebuilds the picker), and Swing throws
     * "Attempt to mutate in notification" for a document changed inside its own notification.
     */
    private void applyPickedWorldSettings(CoopCampaignPicker.Entry entry) {
        String picked = entry == null ? CoopCampaignPicker.NEW_CAMPAIGN_ID : entry.campaignId();
        if (picked.equals(pickedCampaignId)) {
            return;
        }
        if (pickedCampaignId.isEmpty()) {
            // Leaving New: whatever is in the controls is the draft, even if it was never generated.
            draftSeed = hostSeedField.getText().trim();
            draftSectorSize = selected(sectorSizeBox);
            draftSectorAge = selected(sectorAgeBox);
        }
        pickedCampaignId = picked;
        String currentSeed = hostSeedField.getText().trim();
        String wantedSeed = CoopCampaignPicker.seedAfterPick(entry, draftSeed, currentSeed);
        String wantedSize = knownOption(sectorSizeBox, "sector size",
                CoopCampaignPicker.sectorSizeAfterPick(entry, draftSectorSize,
                        selected(sectorSizeBox)));
        String wantedAge = knownOption(sectorAgeBox, "star age",
                CoopCampaignPicker.sectorAgeAfterPick(entry, draftSectorAge,
                        selected(sectorAgeBox)));
        boolean seedChanged = !wantedSeed.equals(currentSeed);
        boolean sizeChanged = !wantedSize.equalsIgnoreCase(selected(sectorSizeBox));
        boolean ageChanged = !wantedAge.equalsIgnoreCase(selected(sectorAgeBox));
        if (!seedChanged && !sizeChanged && !ageChanged) {
            return;
        }
        LOG.info("Campaign picked (" + (picked.isEmpty() ? "new" : picked) + "); seed=" + wantedSeed
                + " sectorSize=" + wantedSize + " sectorAge=" + wantedAge);
        SwingUtilities.invokeLater(() -> {
            writingSeedField = true;
            writingWorldBoxes = true;
            try {
                if (seedChanged) {
                    hostSeedField.setText(wantedSeed);
                }
                if (sizeChanged) {
                    select(sectorSizeBox, wantedSize, wantedSize);
                }
                if (ageChanged) {
                    select(sectorAgeBox, wantedAge, wantedAge);
                }
            } finally {
                writingSeedField = false;
                writingWorldBoxes = false;
            }
            refreshInvitePreview();
        });
    }

    /**
     * {@code wanted} when the drop-down has it, and whatever is selected now when it does not. A
     * value this launcher does not offer can only come from a save index written by another version
     * of the mod, and snapping the box to its first entry would put a sector size in the invite that
     * nobody chose.
     */
    private static String knownOption(JComboBox<String> box, String what, String wanted) {
        String trimmed = wanted == null ? "" : wanted.trim();
        for (int i = 0; i < box.getItemCount(); i++) {
            if (trimmed.equalsIgnoreCase(box.getItemAt(i))) {
                return trimmed;
            }
        }
        if (!trimmed.isEmpty()) {
            LOG.warn("The save index records " + what + " \"" + trimmed + "\", which this"
                    + " launcher does not offer; leaving the drop-down where it is");
        }
        return selected(box);
    }

    /**
     * A move of either world drop-down. While a new campaign is picked the boxes <em>are</em> the
     * draft, so the draft follows them; picking a saved campaign disables both, so nothing but our
     * own write can get here otherwise.
     */
    private void onWorldBoxEdited() {
        if (writingWorldBoxes) {
            // Our own write, made to match a pick that has already been applied everywhere.
            return;
        }
        if (CoopCampaignPicker.worldControlsEnabled(selectedCampaignEntry())) {
            draftSectorSize = selected(sectorSizeBox);
            draftSectorAge = selected(sectorAgeBox);
        }
        refreshInvitePreview();
    }

    /**
     * A keystroke in the seed box. While a new campaign is picked the box <em>is</em> the draft, so
     * the draft follows it; the picker redraws either way because the New entry quotes the seed.
     */
    private void onSeedFieldEdited() {
        if (writingSeedField) {
            // Our own write, made to match a pick that has already been applied everywhere.
            return;
        }
        if (CoopCampaignPicker.worldControlsEnabled(selectedCampaignEntry())) {
            draftSeed = hostSeedField.getText().trim();
        }
        refreshCampaignEntries();
    }

    private CoopCampaignPicker.Entry selectedCampaignEntry() {
        Object item = campaignBox == null ? null : campaignBox.getSelectedItem();
        return item instanceof CoopCampaignPicker.Entry entry ? entry : null;
    }

    /** The campaign the host picked, {@code ""} for a new one. */
    private String selectedCampaignId() {
        CoopCampaignPicker.Entry entry = selectedCampaignEntry();
        return entry == null ? "" : entry.campaignId();
    }

    /** The guest's line, which only says anything once an invite has parsed. */
    private void refreshGuestSaveHint() {
        if (guestSaveHint == null) {
            return;
        }
        if (!guestInviteAccepted) {
            guestSaveHint.setText("");
            guestSaveHint.setVisible(true);
            return;
        }
        setHint(guestSaveHint,
                CoopCampaignPicker.hint(invitedCampaignId, saveIndex, ZoneId.systemDefault()), true);
    }

    /**
     * Writes one hint line. A line that names a save is picked out in the accent colour, because it
     * is the one sentence on the card the player has to act on before pressing anything.
     */
    private void setHint(JTextArea target, String text, boolean visible) {
        text = text.replace("seed above", "shared seed");
        target.setText(CoopLauncherUi.brief(text, 165));
        target.setToolTipText(text);
        target.setForeground(text.startsWith("Load the save") ? CoopTheme.ACCENT : CoopTheme.MUTED);
        target.setVisible(true);
        if (target.getParent() != null) {
            target.getParent().revalidate();
        }
    }

    /** A host always has a seed: a new campaign without one cannot be matched by the guest. */
    private void maybeGenerateHostSeed() {
        if (!hostSeedField.getText().trim().isEmpty()) {
            return;
        }
        String seed = CoopSeeds.generate();
        draftSeed = seed;
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
        connectionButton.setEnabled(!checkingConnection && !mappingCleanupPending && !gameRunning
                && (hostSegment.isSelected() ? validPort(hostPortField.getText())
                : !guestHostField.getText().trim().isEmpty() && validPort(guestPortField.getText())));
        connectionButton.setText(checkingConnection || mappingCleanupPending ? "Checking…"
                : connectionChecked ? "Check again" : "Check connection");
        refreshGuestSummary();
        if (gameRunning) {
            footerHint.setText("Press Play in Starsector's launcher, then start or load your campaign.");
            footerHint.setForeground(CoopTheme.MUTED);
        } else if (reason != null) {
            footerHint.setText(reason);
            footerHint.setForeground(CoopTheme.WARN);
        } else {
            footerHint.setText("Opens Starsector's launcher. Then press Play.");
            footerHint.setForeground(CoopTheme.MUTED);
        }
        launchButton.setToolTipText(reason);
    }

    private String launchBlockedReason() {
        if (gameRunning) {
            return "Starsector is already running from this launcher.";
        }
        if (layout == null) {
            return "Choose your Starsector folder in Installation details.";
        }
        if (CoopInstallCheck.blocked(installRows)) {
            return "Resolve the install problems in Details before launching.";
        }
        if (mappingCleanupPending || checkingConnection) {
            return "Finishing the connection check before launching.";
        }
        if (hostSegment.isSelected()) {
            if (!validPort(hostPortField.getText())) {
                return "The port has to be a number between 1 and 65535.";
            }
            if (CoopSeeds.validate(hostSeedField.getText().trim()) != null) {
                return "Set a valid sector seed in Campaign settings.";
            }
            return null;
        }
        if (guestHostField.getText().trim().isEmpty()) {
            return "Paste the invite from your host, or type the host address in.";
        }
        if (!guestInviteField.getText().trim().isEmpty() && !guestInviteAccepted) {
            return "Fix the invite, or clear it to use a manual connection.";
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

    /** A document listener that runs the same action on every kind of edit. */
    private static DocumentListener onAnyEdit(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        };
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
    private void noticeHostPasswordCleared() {
        if (writingHostPassword || hostPasswordCleared) {
            return;
        }
        if (password(hostPasswordField).isEmpty()) {
            hostPasswordCleared = true;
            LOG.info("The player emptied the host password field; it stays empty for this run");
        }
    }

    /** Keeps the read-only invite line in step with the host fields. */
    private void refreshInvitePreview() {
        if (invitePreviewField == null) {
            return;
        }
        String invite = buildInvite(false);
        invitePreviewField.setText(invite == null ? "" : invite);
        invitePreviewField.setCaretPosition(0);
        boolean validSeed = CoopSeeds.validate(hostSeedField.getText().trim()) == null;
        boolean missingAddress = publicAddressField.getText().trim().isEmpty();
        copyInviteButton.setEnabled(!addressLookupRunning && validSeed
                && validPort(hostPortField.getText()) && (invite != null || missingAddress));
        copyInviteButton.setText(addressLookupRunning ? "Looking up…"
                : invite != null && invite.equals(copiedInvite) ? "Copied" : "Copy invite");
        hostEndpoint.setText(missingAddress ? "No address selected"
                : publicAddressField.getText().trim() + ":" + hostPortField.getText().trim()
                + (password(hostPasswordField).isEmpty() ? " · Open session" : " · Password set"));
        hostEndpoint.setToolTipText(hostEndpoint.getText());
        worldSummary.setText(selected(sectorSizeBox) + " sector · " + selected(sectorAgeBox)
                + " stars · " + (!validSeed ? "Seed needs attention"
                : selectedCampaignId().isEmpty() ? "Seed generated" : "Saved campaign"));
        worldSummary.setToolTipText("Sector seed: " + hostSeedField.getText().trim());
        String feedback = addressLookupRunning ? "Looking up your address…"
                : !validSeed ? "Set a valid seed in Customize."
                : !validPort(hostPortField.getText()) ? "Set a valid port in Edit connection."
                : missingAddress ? "Copy invite to look up your address, or edit the connection."
                : invite == null ? "Check the address in Edit connection."
                : invite.equals(copiedInvite) ? "Invite copied · Send it to your partner"
                : !copiedInvite.isEmpty() ? "Settings changed · Copy a fresh invite" : "Ready to share";
        inviteFeedback.setText(feedback);
        inviteFeedback.setForeground(invite == null ? CoopTheme.MUTED : CoopTheme.OK);
        updateLaunchGate();
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
                    selected(sectorSizeBox), selected(sectorAgeBox), selectedCampaignId());
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
            installSummary.set("Install ready", CoopTheme.OK);
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
            } else if (row.fixable() != null) {
                CoopInstallFixer.Target target = row.fixable();
                String label = row.label();
                trailing = CoopTheme.inline("Fix");
                trailing.setToolTipText("Let the launcher make this edit for you. vmparams is backed"
                        + " up to vmparams.backup first.");
                trailing.addActionListener(event -> applyInstallFix(List.of(target), label));
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
        Dot dot = new Dot(CoopTheme.statusColor(row.status()));

        JLabel label = new JLabel(row.label());
        label.setForeground(CoopTheme.TEXT);

        JTextArea detail = CoopTheme.paragraph(row.detail());
        detail.setToolTipText(row.detail());

        JLabel fix = null;
        if (!row.fix().isEmpty()) {
            fix = new JLabel("<html><body style='width: 460px'>" + escape(row.fix())
                    + "</body></html>");
            fix.setForeground(CoopTheme.MUTED);
            fix.setFont(fix.getFont().deriveFont(Font.ITALIC, (float) fix.getFont().getSize() - 1f));
        }

        JPanel panel = new JPanel(new InstallRowLayout(dot, label, detail, trailing, fix));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        panel.add(dot);
        panel.add(label);
        panel.add(detail);
        if (trailing != null) {
            panel.add(trailing);
        }
        if (fix != null) {
            panel.add(fix);
        }
        return panel;
    }

    /**
     * One install row: a status dot, the check's name, the wrapping detail, an optional button on
     * the same line, and the italic fix line under the name.
     *
     * <p>Laid out by hand because the detail's height is a function of the width the dialog hands
     * the row - a 200-character detail is one line in a wide window and four in a narrow one - and
     * every stock layout manager wants a preferred height before anything has a width. Measured
     * that way, a row is capped at one line and the rest of its text is drawn over the rows above
     * and below it.
     */
    private static final class InstallRowLayout implements LayoutManager {
        /** Cell padding from the GridBagLayout this replaced; unscaled, as it was there. */
        private static final int DOT_LEFT = 2;
        private static final int DOT_TOP = 5;
        private static final int DOT_GAP = 10;
        private static final int LABEL_GAP = 8;
        private static final int TRAILING_GAP = 8;
        private static final int FIX_GAP = 2;

        private final Component dot;
        private final Component label;
        private final Component detail;
        private final Component trailing;
        private final Component fix;

        InstallRowLayout(Component dot, Component label, Component detail, Component trailing,
                         Component fix) {
            this.dot = dot;
            this.label = label;
            this.detail = detail;
            this.trailing = trailing;
            this.fix = fix;
        }

        @Override
        public void addLayoutComponent(String name, Component child) {
        }

        @Override
        public void removeLayoutComponent(Component child) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                int given = parent.getWidth() - insets.left - insets.right;
                // Before the first layout the row has no width. Measure against a plausible one
                // rather than an unwrapped line: too tall leaves a gap for one pass, too short
                // overlaps the neighbours.
                int width = given > 0 ? given : com.formdev.flatlaf.util.UIScale.scale(360);
                return new Dimension(width + insets.left + insets.right,
                        arrange(parent, width, false) + insets.top + insets.bottom);
            }
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                arrange(parent, Math.max(1, parent.getWidth() - insets.left - insets.right), true);
            }
        }

        /** Returns the height this row needs at {@code width}, placing its parts when asked to. */
        private int arrange(Container parent, int width, boolean place) {
            Insets insets = parent.getInsets();
            int left = insets.left;
            int top = insets.top;
            int right = left + width;

            Dimension dotSize = dot.getPreferredSize();
            Dimension labelSize = label.getPreferredSize();
            int labelX = left + DOT_LEFT + dotSize.width + DOT_GAP;
            int detailX = labelX + labelSize.width + LABEL_GAP;
            Dimension trailingSize = trailing == null ? new Dimension() : trailing.getPreferredSize();
            int detailRight = right - (trailing == null ? 0 : trailingSize.width + TRAILING_GAP);
            int detailWidth = Math.max(1, detailRight - detailX);
            // The wrapped height is only knowable once the text area has been told how wide it is.
            detail.setSize(detailWidth, Math.max(1, detail.getHeight()));
            int detailHeight = detail.getPreferredSize().height;

            int firstLine = Math.max(Math.max(DOT_TOP + dotSize.height, labelSize.height),
                    Math.max(detailHeight, trailingSize.height));
            if (place) {
                dot.setBounds(left + DOT_LEFT, top + DOT_TOP, dotSize.width, dotSize.height);
                label.setBounds(labelX, top, labelSize.width, labelSize.height);
                detail.setBounds(detailX, top, detailWidth, detailHeight);
                if (trailing != null) {
                    trailing.setBounds(detailRight + TRAILING_GAP, top,
                            trailingSize.width, trailingSize.height);
                }
            }
            int height = firstLine;
            if (fix != null) {
                // The fix line is html with its own wrap width, so it is drawn at the width it was
                // measured at; anything wider and it would re-wrap into fewer lines than measured.
                Dimension fixSize = fix.getPreferredSize();
                int fixWidth = Math.min(Math.max(1, right - labelX), fixSize.width);
                if (place) {
                    fix.setBounds(labelX, top + firstLine + FIX_GAP, fixWidth, fixSize.height);
                }
                height += FIX_GAP + fixSize.height;
            }
            return height;
        }
    }

    // ---- fixing the install ---------------------------------------------------------------------

    /**
     * Runs one or more install fixes off the event dispatch thread and re-checks the install when
     * they land. Every verdict, including "nothing to change", goes into the log drawer: a button
     * that changes a red row to green without saying what it did to a file the player did not know
     * they had is worse than no button.
     */
    private void applyInstallFix(List<CoopInstallFixer.Target> targets, String what) {
        if (layout == null) {
            append("Point the launcher at your Starsector install first.");
            return;
        }
        LOG.info("Install fix requested for " + what + " " + targets);
        append("Fixing: " + what + ".");
        CoopInstallLayout install = layout;
        List<CoopInstallFixer.Target> wanted = List.copyOf(targets);
        background.submit(() -> {
            List<CoopInstallFixer.Result> results = new ArrayList<>();
            for (CoopInstallFixer.Target one : wanted) {
                try {
                    results.add(CoopInstallFixer.apply(install, one));
                } catch (Exception ex) {
                    LOG.error("The install fix for " + one + " threw", ex);
                    results.add(new CoopInstallFixer.Result(false, false,
                            "The fix for " + one + " failed: " + ex));
                }
            }
            SwingUtilities.invokeLater(() -> finishInstallFix(results));
        });
    }

    /** The event-dispatch-thread half of {@link #applyInstallFix}. */
    private void finishInstallFix(List<CoopInstallFixer.Result> results) {
        boolean denied = false;
        for (CoopInstallFixer.Result result : results) {
            LOG.info("Install fix: changed=" + result.changed() + " accessDenied="
                    + result.accessDenied() + " " + result.message());
            append(result.message());
            denied = denied || result.accessDenied();
        }
        refreshInstallRows();
        if (denied) {
            offerElevatedRelaunch();
        }
    }

    /**
     * The install is somewhere this launcher may not write - almost always {@code Program Files},
     * where Windows hands an unelevated process a read-only view of the folder. Offers to start the
     * same launcher again with administrator rights and let that copy make the edit.
     */
    private void offerElevatedRelaunch() {
        if (layout == null) {
            return;
        }
        if (alreadyElevated) {
            // This copy is the elevated one and the write still failed, so a second UAC prompt
            // would only produce a third launcher window with the same answer.
            LOG.warn("The elevated copy could not write either; not asking again");
            append("Even with administrator rights Windows refused the write. Something else owns"
                    + " those files - an antivirus, a read-only flag, or a folder the game was"
                    + " installed into by another account.");
            appendManualFix();
            return;
        }
        int answer = JOptionPane.showConfirmDialog(frame,
                "Windows would not let the launcher write into your Starsector folder.\n\n"
                        + "That is what happens when the game is installed under Program Files. The"
                        + " launcher can start itself again with administrator rights and make the"
                        + " edit from there.\n\n"
                        + "Restart the launcher as administrator?",
                "Starsector Coop", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            LOG.info("The elevated relaunch was declined in the dialog");
            append("Left the files alone. To do it by hand:");
            appendManualFix();
            return;
        }
        List<String> command = elevatedRelaunchCommand(System.getProperty("java.home"),
                System.getProperty("java.class.path"), layout.installRoot().getPath());
        LOG.info("Asking Windows for administrator rights: " + command);
        append("Asking Windows for administrator rights. Approve the prompt; a second launcher"
                + " window opens and this one closes.");
        background.submit(() -> {
            String failure = elevatedRelaunchFailure(command);
            SwingUtilities.invokeLater(() -> {
                if (failure == null) {
                    LOG.info("The elevated launcher started; closing this one");
                    shutdown();
                    return;
                }
                LOG.warn("The elevated relaunch did not happen: " + failure);
                append("The launcher did not restart with administrator rights: " + failure + ".");
                appendManualFix();
                fail("The launcher could not get administrator rights, so the edit was not made."
                        + " The exact steps are in the log below and in docs/player/INSTALL.md"
                        + " section 3; press Guide to open it.");
            });
        });
    }

    /** Puts the manual instructions for every outstanding fixable row into the log drawer. */
    private void appendManualFix() {
        for (CoopInstallCheck.Row row : installRows) {
            if (row.fixable() != null && row.status() != CoopInstallCheck.Status.OK) {
                append("  " + row.label() + ": " + row.fix());
            }
        }
        append("Both edits are written out in docs/player/INSTALL.md, sections 3 and 4. Press Guide"
                + " to open it.");
    }

    /**
     * The command that starts this same launcher elevated. Reconstructed from the running JVM
     * rather than hardcoded, so it follows the install the player actually started from.
     *
     * <p><b>Why {@code [char]34} instead of a double quote.</b> The whole {@code -Command} value
     * travels as one argument through {@code ProcessBuilder}, which wraps an argument containing
     * spaces in double quotes of its own and does not escape the ones already inside it - the
     * classpath's quotes would be eaten and an install under {@code Program Files} would arrive as
     * two arguments. Building the quotes on the PowerShell side keeps every double quote out of the
     * Java argument. Single quotes are doubled, which is PowerShell's own escape.
     */
    static List<String> elevatedRelaunchCommand(String javaHome, String classPath,
                                                String workingDirectory) {
        String javaw = javaHome + File.separator + "bin" + File.separator + "javaw.exe";
        String arguments = "('-cp ' + [char]34 + " + psQuote(classPath) + " + [char]34 + ' "
                + CoopLauncherApp.class.getName() + " " + APPLY_FIX_FLAG + "')";
        String command = "Start-Process -FilePath " + psQuote(javaw)
                + " -ArgumentList " + arguments
                + " -WorkingDirectory " + psQuote(workingDirectory)
                + " -Verb RunAs -ErrorAction Stop";
        return List.of("powershell", "-NoProfile", "-Command", command);
    }

    /** A PowerShell single-quoted literal. The only escape inside one is a doubled quote. */
    private static String psQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }

    /**
     * Runs the elevated relaunch and says why it did not happen, or {@code null} when it did.
     *
     * <p>Deliberately thin: none of this can be unit-tested, because the answer comes from a UAC
     * prompt. {@code -ErrorAction Stop} is what turns a declined prompt into a non-zero exit -
     * without it {@code Start-Process}'s failure is a non-terminating error and PowerShell still
     * exits 0.
     */
    private static String elevatedRelaunchFailure(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroy();
                return "the Windows prompt was never answered";
            }
            int exit = process.exitValue();
            if (exit == 0) {
                return null;
            }
            return "PowerShell exited " + exit + ", which is what a refused prompt looks like";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "the wait was interrupted";
        } catch (Exception ex) {
            return String.valueOf(ex);
        }
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
        connectionSummary.setText(CoopLauncherUi.brief(text, 135));
        connectionSummary.setToolTipText(text);
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
        if (!setClipboard(invite)) {
            inviteFeedback.setText("Clipboard unavailable · Copy the full invite in Edit connection.");
            inviteFeedback.setForeground(CoopTheme.WARN);
            return;
        }
        copiedInvite = invite;
        refreshInvitePreview();
        LOG.info("Invite copied for " + address + ":" + hostPortField.getText().trim() + " seed="
                + hostSeedField.getText().trim() + " password="
                + (password(hostPasswordField).isEmpty() ? "none" : "set"));
        append("Invite copied. Send it to your partner, then check the connection.");
    }

    private void pasteInvite() {
        LOG.info("Paste invite pressed");
        String text = readClipboard().trim();
        if (text.isEmpty()) {
            guestInviteNote.setText("The clipboard is empty. Copy your host's invite first.");
            guestInviteNote.setForeground(CoopTheme.WARN);
            guestInviteField.requestFocusInWindow();
            return;
        }
        writingGuestInvite = true;
        try {
            guestInviteField.setText(text);
        } finally {
            writingGuestInvite = false;
        }
        applyInviteText(text, true);
        guestInviteField.setCaretPosition(0);
    }

    private void onGuestInviteTyped() {
        if (writingGuestInvite) {
            return;
        }
        String text = guestInviteField.getText().trim();
        if (text.isEmpty()) {
            guestInviteNote.setForeground(CoopTheme.MUTED);
            guestInviteNote.setText("Paste an invite, or enter the connection manually.");
            guestInviteAccepted = false;
            invitedCampaignId = "";
            refreshGuestSaveHint();
            updateLaunchGate();
            return;
        }
        applyInviteText(text, false);
    }

    private void applyInviteText(String text, boolean loud) {
        CoopInvite.Parsed parsed = CoopInvite.parse(text);
        if (!parsed.ok()) {
            guestInviteNote.setForeground(CoopTheme.FAIL);
            guestInviteNote.setText(CoopLauncherUi.brief("Not a usable invite: " + parsed.error(), 150));
            guestInviteNote.setToolTipText(parsed.error());
            guestInviteAccepted = false;
            invitedCampaignId = "";
            refreshGuestSaveHint();
            updateLaunchGate();
            if (loud) {
                LOG.warn("Invite could not be parsed: " + parsed.error());
                guestInviteField.requestFocusInWindow();
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
        guestInviteNote.setText("Invite accepted · "
                + (invite.password().isEmpty() ? "Open session" : "Password included"));
        guestInviteNote.setToolTipText(summary);
        invitedCampaignId = invite.campaignId();
        guestInviteAccepted = true;
        refreshGuestSaveHint();
        refreshGuestSummary();
        updateLaunchGate();
        LOG.info("Invite accepted: " + invite);
        append(summary);
        append(guestSaveHint.getText());
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
        if (addressLookupRunning) {
            return;
        }
        addressLookupRunning = true;
        publicLookupButton.setEnabled(false);
        publicLookupButton.setText("Looking up…");
        refreshInvitePreview();
        LOG.info("Public address lookup started");
        append("Looking up your public address.");
        String textWhenStarted = publicAddressField.getText();
        background.submit(() -> {
            CoopPublicAddress.Lookup result = CoopPublicAddress.lookup();
            SwingUtilities.invokeLater(() -> {
                addressLookupRunning = false;
                publicLookupButton.setEnabled(true);
                publicLookupButton.setText("Look up");
                refreshInvitePreview();
                if (result.ok()) {
                    if (!shouldApplyLookedUpAddress(automatic, textWhenStarted,
                            publicAddressField.getText())) {
                        LOG.info("Public address lookup returned " + result.address()
                                + "; keeping the address typed while it ran");
                        append("Your public address is " + result.address() + ", but you typed "
                                + publicAddressField.getText().trim()
                                + " while the lookup ran, so that is what the invite uses.");
                        if (then != null) {
                            then.run();
                        }
                        return;
                    }
                    publicAddressField.setText(result.address());
                    LOG.info("Public address lookup returned " + result.address());
                    append("Your public address is " + result.address()
                            + ". Overwrite it if you connect over a LAN or a VPN.");
                } else {
                    LOG.warn("Public address lookup failed: " + result.error());
                    append(result.error());
                    inviteFeedback.setText("Address lookup failed · Enter an address in Edit connection.");
                    inviteFeedback.setForeground(CoopTheme.WARN);
                    inviteFeedback.setToolTipText(result.error());
                }
                if (then != null) {
                    then.run();
                }
            });
        });
    }

    private void checkMyConnection() {
        if (checkingConnection || mappingCleanupPending) {
            return;
        }
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
        cancelConnectionCheck("a new connection check started");
        int generation = checkGeneration;
        checkingConnection = true;
        connectionChecked = false;
        setConnectionStatus("Checking connection…", CoopIcons.Symbol.BUSY, CoopTheme.MUTED);
        updateLaunchGate();
        closeListener("a new connection check started");
        append("Checking port " + port + ". This takes a few seconds.");
        Chip working = new Chip("asking the router", CoopTheme.INFO);
        setChips(List.of(working));
        note("Asking your router to open port " + port + ", the way the game does at startup.");

        boolean mappingEnabled = !"off".equalsIgnoreCase(selected(portMappingBox));
        CoopPortMapper mapper = CoopPortMapper.start(port, mappingEnabled, System::currentTimeMillis);
        connectionMapper = mapper;
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
            connectionMapper = null;
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
                updatingConnectionAddress = true;
                try {
                    publicAddressField.setText(result.externalAddress());
                } finally {
                    updatingConnectionAddress = false;
                }
            }
            // shutdown() drives its own bounded release loop (up to 1.2 s of busy waiting against
            // the injected clock), so it has to leave the event dispatch thread. The mapper is not
            // being ticked any more at this point, so handing it over is a clean transfer.
            releaseMapper(mapper, () -> {
                append("Released the router mapping so the game can make its own at startup.");
                if (!checkResultStillApplies(generation, checkGeneration, gameRunning,
                        hostSegment.isSelected())) {
                    // A role or endpoint change invalidates the result while cleanup is running.
                    LOG.info("Connection check " + generation + " is no longer current; not"
                            + " opening the launcher listener on port " + port);
                    return;
                }
                boolean listening = openListener(port);
                checkingConnection = false;
                connectionChecked = true;
                showHostChips(port, result, listening);
            });
        });
        checkTimer.start();
    }

    private void showHostChips(int port, CoopPortMapper.Result result, boolean listening) {
        boolean ready = listening && result.mapped() && !result.cgnat();
        setConnectionStatus(!listening ? "Connection needs attention"
                : result.mapped() && !result.cgnat() ? "Router prepared"
                : "Manual connection setup", ready ? CoopIcons.Symbol.CHECK : CoopIcons.Symbol.ALERT,
                ready ? CoopTheme.OK : CoopTheme.WARN);
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
                    + " partner to press Check connection. The full doctor block is in the log.");
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
        if (!listening) {
            note("Port " + port + " is already in use. Close the other listener or choose another port.");
        } else if (result.mapped() && !result.cgnat() && !result.externalEndpoint().isEmpty()) {
            connectionSummary.setText("Copy the invite, then ask your partner to check the connection.");
        }
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
        if (checkingConnection || mappingCleanupPending || gameRunning) {
            return;
        }
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
        cancelConnectionCheck("a new guest test started");
        int generation = checkGeneration;
        checkingConnection = true;
        connectionChecked = false;
        setConnectionStatus("Checking connection…", CoopIcons.Symbol.BUSY, CoopTheme.MUTED);
        updateLaunchGate();
        append("Testing " + host + ":" + port + ".");
        setChips(List.of(new Chip("reaching " + host + ":" + port, CoopTheme.INFO)));
        note("");
        background.submit(() -> {
            CoopLauncherProbe.Result result = CoopLauncherProbe.GuestProber.probe(host, port);
            SwingUtilities.invokeLater(() -> {
                if (generation != checkGeneration || hostSegment.isSelected() || gameRunning) {
                    return;
                }
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
                chips.add(new Chip(result.tcpReachable() ? "TCP passed" : "TCP failed",
                        result.tcpReachable() ? CoopTheme.OK : CoopTheme.FAIL));
                chips.add(new Chip(result.launcherAnswered()
                        ? "launcher " + result.launcherVersion()
                        : result.tcpReachable() ? "Not a launcher" : "Launcher not reached",
                        result.launcherAnswered() ? CoopTheme.OK
                                : result.tcpReachable() ? CoopTheme.WARN : CoopTheme.FAIL));
                chips.add(new Chip(result.udpEchoed() ? "UDP passed"
                        : result.launcherAnswered() ? "UDP failed" : "UDP not tested", result.udpEchoed() ? CoopTheme.OK
                        : result.launcherAnswered() ? CoopTheme.FAIL : CoopTheme.INFO));
                chips.add(new Chip(result.rttMillis() < 0 ? "no round trip"
                        : result.rttMillis() + " ms", result.rttMillis() < 0 ? CoopTheme.INFO
                        : CoopTheme.OK));
                setChips(chips);
                note(result.message());
                if (result.launcherAnswered() && result.udpEchoed()) {
                    connectionSummary.setText("TCP passed · UDP passed · " + result.rttMillis()
                            + " ms. Launch when your host does.");
                }
                checkingConnection = false;
                connectionChecked = true;
                boolean ready = result.launcherAnswered() && result.udpEchoed();
                setConnectionStatus(ready ? "Host reachable" : "Connection needs attention",
                        ready ? CoopIcons.Symbol.CHECK : CoopIcons.Symbol.ALERT,
                        ready ? CoopTheme.OK : CoopTheme.WARN);
                updateLaunchGate();
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
                for (String line : bugReportStatusLines(finished)) {
                    append(line);
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
                updateChip.setText("Update available");
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
        // Which campaign this launch is for, so the mod can say so in-game when the save the player
        // loads belongs to a different one. Blank for a new campaign, and blank for a guest whose
        // host is on a release that did not put a campaign id in the invite; a blank value takes
        // the key back out of the file, which is what "nothing expected" has to look like.
        String expectedCampaign = host ? selectedCampaignId() : invitedCampaignId;
        owned.put(CoopLauncherConfig.EXPECTED_CAMPAIGN_ID, expectedCampaign);
        if (expectedCampaign.isEmpty()) {
            append("This launch is for a new campaign: start a New Game with the seed above.");
        } else {
            String advice = CoopCampaignPicker.hint(expectedCampaign, saveIndex,
                    ZoneId.systemDefault());
            LOG.info("Launch is for campaign " + expectedCampaign + "; " + advice);
            append(advice);
        }
        owned.put(CoopLauncherConfig.PORT_MAPPING, selected(portMappingBox));
        owned.put(CoopLauncherConfig.RECONNECT_GRACE_SECONDS,
                String.valueOf(reconnectGraceSpinner.getValue()));
        owned.put(CoopLauncherConfig.HUD_CORNER, selected(hudCornerBox));
        owned.put(CoopLauncherConfig.MARK_KEY, markKeyField.getText().trim());
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
        // The checkbox and the port field are remembered as themselves, and coop.debug.bridge - the
        // only one of the three the game reads - is what they add up to for this role. Remembering
        // the field separately is what lets the box be unticked without losing a typed port.
        owned.put(CoopLauncherConfig.LAUNCHER_BRIDGE_ENABLED,
                nonDefault(bridgeEnabledBox, CoopLauncherConfig.LAUNCHER_BRIDGE_ENABLED));
        owned.put(CoopLauncherConfig.LAUNCHER_BRIDGE_PORT,
                nonDefault(bridgePortSpinner, CoopLauncherConfig.LAUNCHER_BRIDGE_PORT));
        int bridgePort = CoopLauncherConfig.bridgePortFor(bridgeEnabledBox.isSelected(),
                ((Number) bridgePortSpinner.getValue()).intValue(), host);
        owned.put(CoopLauncherConfig.DEBUG_BRIDGE,
                bridgePort == 0 ? "" : String.valueOf(bridgePort));
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
            // The tick was consumed by the settings file this launch just wrote, and nothing is
            // going to exit to clear it: without this, a launch that never started leaves the
            // one-shot consent in coop_options.json.data for the next plain start of the game.
            clearAdoptConsent(layout, "the launch failed to start");
            fail("Could not start the game:\n\n" + ex.getMessage());
            return;
        }
        long pid = gameProcess.pid();
        LOG.info("Started starsector.exe pid " + pid);
        append("Starsector started (pid " + pid + "). This window keeps showing the co-op lines from"
                + " the game log.");
        launchButton.setText("Game running");
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
                launchButton.setText("Launch Starsector");
                gameRunning = false;
                updateLaunchGate();
                // The session just wrote saves. The picker and both hints are about to be read by
                // somebody deciding what to load next time, so they must not still show what was
                // on disk before this run.
                refreshSaveIndex("the game exited");
            });
        });
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
        clearAdoptConsent(target.coopOptions(), reason);
    }

    /**
     * The file half of it, static so the three moments that have to clear the tick - a launcher
     * start that found one left behind, a game that exited, and a launch that threw before the game
     * ever started - all go through one tested piece of code. Never throws: a settings file that
     * cannot be rewritten is a warning in the log, not a dialog on top of whatever just went wrong.
     *
     * @return true when the file was rewritten
     */
    static boolean clearAdoptConsent(File options, String reason) {
        if (options == null) {
            return false;
        }
        try {
            if (CoopLauncherConfig.clearAdoptCampaignConsent(options)) {
                LOG.info("Cleared " + CoopLauncherConfig.ADOPT_CAMPAIGN_ID + " from " + options
                        + " because " + reason);
                return true;
            }
            return false;
        } catch (Exception ex) {
            LOG.warn("Could not clear " + CoopLauncherConfig.ADOPT_CAMPAIGN_ID + " from " + options,
                    ex);
            return false;
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
        if (connectionMapper != null) {
            CoopPortMapper mapper = connectionMapper;
            connectionMapper = null;
            releaseMapper(mapper, () -> { });
        }
    }

    /** Cleanup is bounded and independent of slow lookups or report writing on the worker. */
    private void releaseMapper(CoopPortMapper mapper, Runnable then) {
        mappingCleanupPending = true;
        Thread release = new Thread(() -> {
            try {
                mapper.shutdown();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    mappingCleanupPending = false;
                    then.run();
                    updateLaunchGate();
                });
            }
        }, "coop-launcher-port-release");
        // Finish releasing the temporary mapping even when the last window is closed.
        release.setDaemon(false);
        release.start();
    }

    /**
     * What the status pane says about a finished bug report, in order.
     *
     * <p>The notes are the point. Every one of them is about what did or did not get taken out of
     * the archive - a settings file that would not parse is left out of it entirely, because the
     * password in it could not be blanked - and until now they only went into {@code report.txt}
     * inside the zip, which is the wrong side of the door: the player is about to post that zip on
     * a public forum, so the news that a file they expected is missing has to reach them before
     * they do, not after somebody asks for it.
     */
    static List<String> bugReportStatusLines(CoopBugReport.Result result) {
        List<String> lines = new ArrayList<>();
        lines.add("Saved " + result.zip() + ". It contains your public address from the doctor"
                + " block and the last two game logs; attach both players' zips to the report.");
        lines.addAll(result.notes());
        if (result.saveInFlight()) {
            lines.add("The newest save was still being written, run the report again in a moment.");
        }
        return List.copyOf(lines);
    }

    /**
     * True when a finished connection check may still open the launcher's listener on the co-op
     * port. Even with controls gated during a check, a queued completion can belong to an old
     * role or endpoint. By the time it arrives the port can belong to the game - and a listener bound then
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
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, statusArea);
        javax.swing.JScrollBar bar = scroll == null ? null : scroll.getVerticalScrollBar();
        boolean following = bar == null || bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
        statusArea.append(stamped + "\n");
        if (following) {
            statusArea.setCaretPosition(statusArea.getDocument().getLength());
        }
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
        CoopTheme.inputHeight(box);
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
        int min = spinnerMin(key);
        int max = spinnerMax(key);
        int start = Integer.parseInt(CoopOptionsRegistry.require(key).defaultValue());
        return new JSpinner(new SpinnerNumberModel(start, Math.min(min, start), Math.max(max, start), step));
    }

    /** The registry's lower bound for an INT key, floored at 0 for a key that declares none. */
    static int spinnerMin(String key) {
        int min = CoopOptionsRegistry.require(key).min();
        return min == Integer.MIN_VALUE ? 0 : min;
    }

    /**
     * The registry's upper bound for an INT key. The registry is the one source: a key that names a
     * real bound (a port, a delay the game clamps anyway) gets exactly that number here, so the
     * spinner cannot offer a value the game would silently reduce.
     *
     * <p>{@code 65535} is only the fallback for a key that is still deliberately unbounded, because
     * a {@link SpinnerNumberModel} needs some maximum and {@link Integer#MAX_VALUE} makes a spinner
     * a player cannot drag anywhere useful. Give the key a real registry bound rather than tuning
     * this number.
     */
    static int spinnerMax(String key) {
        int max = CoopOptionsRegistry.require(key).max();
        return max == Integer.MAX_VALUE ? 65535 : max;
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

    private boolean setClipboard(String text) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
            return true;
        } catch (Exception ex) {
            LOG.warn("Could not write to the clipboard", ex);
            append("Could not write to the clipboard; copy the line above by hand.");
            return false;
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
