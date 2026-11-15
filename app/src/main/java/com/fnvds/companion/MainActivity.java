package com.fnvds.companion;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BRIDGE_PORT = 47888;
    private static final int BACKGROUND = Color.rgb(5, 10, 8);
    private static final String[] PIPBOY_COLORS = {"AMBER", "BLUE", "GREEN", "WHITE"};
    private static final int[] PIPBOY_COLOR_VALUES = {
            Color.rgb(225, 182, 66), Color.rgb(76, 150, 255),
            Color.rgb(80, 255, 120), Color.rgb(235, 245, 238)
    };
    private static final String[] MAIN_SECTIONS = {"STATS", "ITEMS", "DATA", "SETTINGS"};
    private static final String[][] SUBSECTIONS = {
            {"STATUS", "S.P.E.C.I.A.L.", "SKILLS", "PERKS", "REPUTATION"},
            {"WEAPONS", "APPAREL", "AID", "MISC", "AMMO"},
            {"MAP", "QUESTS", "MISC", "RADIO", "GENERAL", "CHALLENGES"},
            {"COLOR"}
    };
    private static final String[][][] INVENTORY_GROUPS = {
            {{"GUNS", "GUNS"}, {"ENERGY", "ENERGY WEAPONS"}, {"EXPLOSIVES", "EXPLOSIVES"},
                    {"MELEE", "MELEE WEAPONS"}, {"UNARMED", "UNARMED"}, {"OTHER", "OTHER"}},
            {{"LIGHT", "LIGHT ARMOUR / CLOTHING"}, {"MEDIUM", "MEDIUM ARMOUR"},
                    {"HEAVY", "HEAVY ARMOUR"}, {"POWER", "POWER ARMOUR"},
                    {"HEADWEAR", "HEADWEAR"}, {"ACCESSORIES", "ACCESSORIES"}},
            {{"STIMPAKS", "STIMPAKS"}, {"CHEMS", "CHEMS / MEDICINE"},
                    {"FOOD", "FOOD / NON-ALCOHOLIC DRINKS"}, {"ALCOHOL", "ALCOHOL"},
                    {"OTHER", "OTHER AID"}},
            {{"MODS", "WEAPON MODS"}, {"KEYS", "KEYS"}, {"BOOKS", "BOOKS"},
                    {"CURRENCY", "CURRENCY"}, {"CARDS", "CARAVAN CARDS"},
                    {"OTHER", "OTHER MISCELLANEOUS / JUNK"}}
    };
    private static final String[] SPECIAL_NAMES = {
            "STRENGTH", "PERCEPTION", "ENDURANCE", "CHARISMA",
            "INTELLIGENCE", "AGILITY", "LUCK"
    };
    private static final String[] SPECIAL_INITIALS = {"S", "P", "E", "C", "I", "A", "L"};
    private static final String[] SKILL_NAMES = {
            "BARTER", "BIG GUNS", "ENERGY WEAPONS", "EXPLOSIVES", "LOCKPICK",
            "MEDICINE", "MELEE WEAPONS", "REPAIR", "SCIENCE", "GUNS",
            "SNEAK", "SPEECH", "SURVIVAL", "UNARMED"
    };
    private static final String[] LIMB_NAMES = {
            "HEAD", "TORSO", "LEFT ARM", "RIGHT ARM", "LEFT LEG", "RIGHT LEG"
    };

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile Socket activeSocket;
    private volatile BufferedWriter bridgeOutput;
    private final Object bridgeWriteLock = new Object();
    // Serialize outgoing commands on a worker thread so socket writes do not block touch handlers.
    private final ExecutorService bridgeCommandExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "FNVDS-Bridge-Commands");
        thread.setDaemon(true);
        return thread;
    });
    private final List<TextView> brightText = new ArrayList<>();
    private final List<TextView> softText = new ArrayList<>();
    private final List<View> panelViews = new ArrayList<>();
    private final List<InventoryGroupHeaderView> inventoryGroupHeaders = new ArrayList<>();
    private final List<View> equippedInventoryRows = new ArrayList<>();
    private final List<TextView> inventoryActionViews = new ArrayList<>();
    private final List<InventoryConditionView> inventoryConditionViews = new ArrayList<>();
    private final List<InventoryDetailsView> inventoryDetailsViews = new ArrayList<>();
    private InventoryDetailsView expandedInventoryView;
    private String expandedInventoryCategory = "";
    private long expandedInventoryForm;
    private long expandedInventoryToken;
    private InventoryEntry pendingExpandedEquip;
    private final java.util.Set<Long> expandedEquipPreviousTokens = new java.util.HashSet<>();
    private long expandedEquipStartedAt;
    private String cachedInventoryDetails = "";
    private boolean cachedInventoryCanMod;
    private boolean cachedInventoryCanDrop;
    private boolean dropItemPending;
    private long lastDropResultSequence;
    private ValueAnimator inventoryDetailAnimator;
    private final Map<Integer, String> hotkeyNames = new HashMap<>();
    private final Map<Integer, String> incomingHotkeyNames = new HashMap<>();
    private HotkeyPickerView activeHotkeyPicker;
    private record WeaponModEntry(long formId, int count, boolean installed, String name) { }
    private final List<WeaponModEntry> weaponMods = new ArrayList<>();
    private final List<WeaponModEntry> incomingWeaponMods = new ArrayList<>();
    private long weaponModsForm, weaponModsToken;
    private long incomingModsForm, incomingModsToken;
    private LinearLayout activeModList;
    private InventoryEntry activeModWeapon;
    private boolean modInstallPending;
    private long lastModResultSequence;
    private final List<NavigationKeyView> mainTabViews = new ArrayList<>();
    private final List<TextView> subTabViews = new ArrayList<>();
    private int accentColor = Color.rgb(255, 182, 66);
    private int secondaryColor = Color.rgb(255, 207, 132);
    private int panelColor = Color.rgb(27, 20, 10);
    private boolean audioEnabled;
    private boolean shutterTransitionsEnabled;
    private boolean navigationAnimating;
    private ToneGenerator uiTone;
    private int selectedSection;
    private int selectedSubsection;
    private int fixedBrightTextCount;
    private int fixedSoftTextCount;
    private TextView connectionText;
    private FrameLayout waitingOverlay;
    private FrameLayout activityOverlay;
    private LinearLayout activityCard;
    private TextView activityMessage;
    private RouteTraceView activityRouteTrace;
    private HolotapeLoadingView activityHolotape;
    private PlayerDeathView activityPlayerDeath;
    private CellChangeView activityCellChange;
    private CraftingBenchView activityCrafting;
    private BarterSummaryView barterSummary;
    private BarterSummaryView lootSummary;
    private final List<BarterSummaryView.Item> incomingLootItems = new ArrayList<>();
    private String incomingLootName = "CONTAINER", lootSignature;
    private final List<BarterSummaryView.Item> incomingBarterItems = new ArrayList<>();
    private String incomingBarterName = "TRADER", barterSignature;
    private int incomingPlayerCaps, incomingMerchantCaps, incomingBarterBalance;
    private ContainerView activityContainer;
    private String gameActivity = "NONE";
    private String gameActivityPlace = "";
    private boolean companionHasOpened;
    private boolean reloadInProgress;
    private volatile boolean interactionBlocked;
    private Dialog contentDialog;
    private GameKeyboardView gameKeyboard;
    private volatile long textEntrySession;
    private MapSearchDialog mapSearchDialog;
    private android.widget.PopupWindow contentPopup;
    private LinearLayout dialogueOverlay;
    private final List<String[]> incomingDialogueChoices = new ArrayList<>();
    private final List<String[]> dialogueChoices = new ArrayList<>();
    private String dialogueSpeaker = "";
    private boolean dialogueReady;
    private boolean dialogueActive;
    private VatsView vatsOverlay;
    private boolean vatsActive;
    private VatsView.State vatsState, incomingVatsState;
    private final List<VatsView.Part> incomingVatsParts = new ArrayList<>();
    private static final Pattern DIALOGUE_CHECK = Pattern.compile("^\\[([^\\]]+?)\\s+(\\d+)(?:\\s*/\\s*(\\d+))?\\]");
    private long dialogueSpeechToken;
    private long dialogueGestureToken;
    private boolean dialogueSkipGesture;
    private boolean dialogueGestureMoved;
    private float dialogueTouchX, dialogueTouchY;
    private boolean navigationSwipeCandidate, navigationSwipeCaptured, navigationSwipeMain;
    private float navigationSwipeX, navigationSwipeY;
    private int navigationSwipeSection, navigationSwipeSubsection;
    private final int[] navigationHitLocation = new int[2];
    private TerminalIntroView terminalIntro;
    private Dialog versionMismatchDialog;
    private final java.util.Set<String> notifiedPluginVersions = new java.util.HashSet<>();
    private LinearLayout mainTabs;
    private LinearLayout subTabs;
    private LinearLayout subNavigation;
    private LinearLayout inventoryOverviewList, healingChoices;
    private int inventoryOverviewSort;
    private boolean healingRadiation;
    private int survivalNeed = -1;
    private final TextView[] inventorySortButtons = new TextView[3];
    private TextView inventoryWeight;
    private String cachedInventoryWeight = "--/--";
    private LinearLayout pageContent;
    private FrameLayout pageFrame;
    private TextView healthValue;
    private TextView actionPointsValue;
    private StatusBarView healthBar, actionPointsBar;
    private TextView statusClock;
    private TextView statusDate, statusLevel;
    private StatusBarView experienceBar;
    private int cachedPlayerLevel;
    private String cachedLevelExperience = "0", cachedLevelExperienceRequired = "0";
    private boolean cachedLevelCapped;
    private String cachedMaxHealth = "--.-", cachedMaxActionPoints = "--.-";
    private String cachedGameDate = "--.--.---- --:--";
    private boolean cachedHardcore;
    private int doctorsBagCount, crippledLimbMask;
    private long lastLimbResultSequence;
    private boolean limbTreatmentPending;
    private TextView radioCurrentValue;
    private RadioWaveformView radioWaveform;
    private boolean cachedRadioPlaying;
    private String renderedRadioStation = "";
    private String cachedRadioStation = "WAITING FOR GAME RADIO DATA";
    private final List<RadioStation> radioStations = new ArrayList<>();
    private final List<RadioStation> incomingRadioStations = new ArrayList<>();
    private String cachedHealth = "--.-";
    private String cachedActionPoints = "--.-";
    private final int[] specialModifiers = new int[7];
    private final String[] cachedSpecialValues = {
            "--.-", "--.-", "--.-", "--.-", "--.-", "--.-", "--.-"
    };
    private final TextView[] specialValues = new TextView[7];
    private final String[] cachedSkillValues = {
            "--.-", "--.-", "--.-", "--.-", "--.-", "--.-", "--.-",
            "--.-", "--.-", "--.-", "--.-", "--.-", "--.-", "--.-"
    };
    private final String[] cachedEffectValues = {"--.-", "--.-", "--.-", "--.-"};
    private final String[] cachedLimbValues = {"--", "--", "--", "--", "--", "--"};
    private LimbConditionView limbConditionView;
    private final List<InventoryEntry> inventoryItems = new ArrayList<>();
    private final List<InventoryEntry> incomingInventoryItems = new ArrayList<>();
    private final List<QuestEntry> questEntries = new ArrayList<>();
    private final List<QuestEntry> incomingQuestEntries = new ArrayList<>();
    private final List<PerkEntry> perkEntries = new ArrayList<>();
    private final java.util.Map<Long, String> perkDescriptions = new java.util.HashMap<>();
    private long expandedPerk, pendingPerkDetail;
    private String perkSignature;
    private final List<PerkEntry> incomingPerkEntries = new ArrayList<>();
    private final List<ReputationEntry> reputationEntries = new ArrayList<>();
    private final List<ReputationEntry> incomingReputationEntries = new ArrayList<>();
    private final List<String[]> generalStats = new ArrayList<>();
    private final List<String[]> incomingGeneralStats = new ArrayList<>();
    private String generalStatsSignature;
    private boolean generalStatsLoaded;
    private final List<ChallengeEntry> challengeEntries = new ArrayList<>();
    private final List<ChallengeEntry> incomingChallenges = new ArrayList<>();
    private final Map<Long, String> challengeDescriptions = new HashMap<>();
    private String challengeSignature;
    private boolean challengesLoaded;
    private long expandedChallenge, pendingChallengeDetail;
    private final List<NoteEntry> noteEntries = new ArrayList<>();
    private final List<NoteEntry> incomingNoteEntries = new ArrayList<>();
    private long playingNoteFormId;
    private long playingNoteDurationMs;
    private long playingNoteElapsedMs;
    private NotePlaybackRulerView activeNotePlaybackRuler;
    private boolean noteScanActive;
    private boolean inventoryLoaded;
    private boolean questsLoaded;
    private boolean perksLoaded;
    private boolean reputationsLoaded;
    private boolean notesLoaded;
    private volatile boolean notesRequested;
    private boolean gameReadyForNotes;
    private String reputationSignature;
    private String cachedKarmaTitle = "UNKNOWN";
    private int cachedKarmaLevel = -1;
    private boolean activeEffectsLoaded;
    private String activeEffectsSignature;
    private final List<String[]> activeEffectEntries = new ArrayList<>();
    private final List<String[]> incomingActiveEffects = new ArrayList<>();
    private Bitmap mapBitmap;
    private final Map<String, Bitmap> streamedMapCache = new HashMap<>();
    private volatile String currentWorldKey = "";
    private long currentMapSpaceId;
    private boolean customMarkerExists;
    private volatile String requestedMapKey;
    private volatile boolean mapRequestPending;
    private long lastMapRequestAt;
    private String mapLoadStatus = "WAITING FOR GAME MAP";
    private ByteArrayOutputStream incomingMapPayload;
    private String incomingMapKey;
    private int incomingMapPayloadLength;
    private int incomingMapDecodedLength;
    private boolean incomingMapCompressed;
    private MapCanvas mapCanvas;
    private volatile boolean localMapSelected;
    private LocalMapCanvas localMapCanvas;
    private Bitmap localMapBitmap;
    private static final int LOCAL_MAP_GRID_SIDE = 5;
    private static final int LOCAL_MAP_TILE_SIZE = 256;
    private static final int LOCAL_MAP_TILE_COUNT = LOCAL_MAP_GRID_SIDE * LOCAL_MAP_GRID_SIDE;
    private final Bitmap[] incomingLocalTiles = new Bitmap[LOCAL_MAP_TILE_COUNT];
    private final Bitmap[] cachedLocalTiles = new Bitmap[LOCAL_MAP_TILE_COUNT];
    private long localMapArea, incomingLocalArea;
    private int localCenterX, localCenterY, incomingLocalCenterX, incomingLocalCenterY;
    private long incomingLocalGeneration, localMapGeneration, localPositionGeneration;
    private float localPlayerX = Float.NaN, localPlayerY = Float.NaN, localHeading;
    private String localStatus = "REQUESTING LOCAL MAP";
    private final java.util.ArrayList<MapMarkerEntry> localMarkers = new java.util.ArrayList<>();
    private final java.util.ArrayList<MapMarkerEntry> incomingLocalMarkers = new java.util.ArrayList<>();
    private long localMarkerArea, incomingLocalMarkerArea;
    private int localMarkerCenterX,localMarkerCenterY,incomingLocalMarkerCenterX,incomingLocalMarkerCenterY;
    private float mapPlayerX = Float.NaN;
    private float mapPlayerY = Float.NaN;
    private float mapPlayerHeading;
    private float customMapX = Float.NaN;
    private float customMapY = Float.NaN;
    private final List<QuestDestination> questDestinations = new ArrayList<>();
    private long questViewSequence = android.os.SystemClock.uptimeMillis() & 0xffffffffL;
    private long pendingQuestViewRequest, pendingQuestViewForm;
    private QuestDestination viewedQuestDestination;
    private long viewedQuestForm;
    private final Map<Long, QuestDestination> incomingQuestDestinations = new LinkedHashMap<>();
    private final List<MapMarkerEntry> mapMarkers = new ArrayList<>();
    private final List<MapMarkerEntry> incomingMapMarkers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Keep gamepad/key focus with the game on the other display; direct touch still works.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        accentColor = getPreferences(MODE_PRIVATE).getInt("pipboy_color", PIPBOY_COLOR_VALUES[0]);
        audioEnabled = getPreferences(MODE_PRIVATE).getBoolean("audio_enabled", true);
        shutterTransitionsEnabled = getPreferences(MODE_PRIVATE).getBoolean("transitions_enabled", true);
        uiTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 55);
        getPreferences(MODE_PRIVATE).edit()
                .remove("map_diag_x")
                .remove("map_diag_y")
                .remove("map_diag_scale")
                .apply();
        buildScreen();
        startBridge();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(14), dp(22), dp(12));
        root.setBackgroundColor(BACKGROUND);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        connectionText = softLabel("CONNECTING", 11, true);
        heading.addView(connectionText, wrap());
        root.addView(heading, matchWrap());

        HorizontalScrollView subScroll = new HorizontalScrollView(this);
        subScroll.setHorizontalScrollBarEnabled(false);
        subScroll.setFillViewport(true);
        subTabs = new LinearLayout(this);
        subTabs.setOrientation(LinearLayout.HORIZONTAL);
        subTabs.setGravity(Gravity.CENTER);
        subScroll.addView(subTabs, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams subScrollParams = matchWrap();
        subScrollParams.topMargin = dp(2);
        subScrollParams.bottomMargin = dp(6);

        pageFrame = new FrameLayout(this);
        pageFrame.setClipChildren(true);
        pageFrame.setClipToPadding(true);
        pageFrame.setBackground(outlineDrawable(accentColor, false));

        ScrollView contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setPadding(dp(12), dp(12), dp(12), dp(12));
        contentScroll.addView(pageContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentParams.setMargins(dp(2), dp(2), dp(2), dp(2));
        pageFrame.addView(contentScroll, contentParams);

        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        frameParams.topMargin = dp(3);
        frameParams.bottomMargin = dp(3);
        root.addView(pageFrame, frameParams);
        subNavigation = new LinearLayout(this);
        subNavigation.setGravity(Gravity.CENTER_VERTICAL);
        subNavigation.addView(subScroll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        inventoryWeight = label(cachedInventoryWeight, 11, true);
        inventoryWeight.setGravity(Gravity.CENTER);
        inventoryWeight.setPadding(dp(8), 0, dp(8), 0);
        inventoryWeight.setMinWidth(dp(60));
        inventoryWeight.setMinHeight(dp(36));
        inventoryWeight.setBackgroundColor(panelColor);
        inventoryWeight.setOnClickListener(view -> showInventoryOverview());
        subNavigation.addView(inventoryWeight, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        root.addView(subNavigation, subScrollParams);

        mainTabs = new LinearLayout(this);
        mainTabs.setOrientation(LinearLayout.HORIZONTAL);
        mainTabs.setGravity(Gravity.CENTER);
        root.addView(mainTabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        buildMainTabs();
        buildSubTabs();
        fixedBrightTextCount = brightText.size();
        fixedSoftTextCount = softText.size();
        renderPage();
        applyThemeColor(accentColor);

        waitingOverlay = new FrameLayout(this);
        waitingOverlay.setBackgroundColor(BACKGROUND);
        waitingOverlay.setClickable(true);
        terminalIntro = new TerminalIntroView(this, accentColor, BuildConfig.VERSION_NAME,
                this::finishIntro);
        waitingOverlay.addView(terminalIntro, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout screen = new FrameLayout(this);
        screen.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialogueOverlay = new LinearLayout(this);
        dialogueOverlay.setOrientation(LinearLayout.VERTICAL);
        dialogueOverlay.setPadding(dp(24), dp(24), dp(24), dp(24));
        dialogueOverlay.setBackgroundColor(BACKGROUND);
        dialogueOverlay.setClickable(true);
        dialogueOverlay.setVisibility(View.GONE);
        screen.addView(dialogueOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        vatsOverlay = new VatsView(this, accentColor, (action, token) -> {
            if (!vatsActive || vatsState == null || !vatsState.ready() || interactionBlocked) return;
            playUiSound();
            sendBridgeCommand("VATSCMD " + Long.toUnsignedString(vatsState.session()) + " " + action + " "
                    + Long.toUnsignedString(token));
        });
        vatsOverlay.setVisibility(View.GONE);
        screen.addView(vatsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.addView(waitingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activityOverlay = new FrameLayout(this);
        activityOverlay.setBackgroundColor(Color.argb(170, 0, 0, 0));
        activityOverlay.setClickable(true);
        activityOverlay.setVisibility(View.GONE);
        activityCard = new LinearLayout(this);
        activityCard.setOrientation(LinearLayout.VERTICAL);
        activityCard.setPadding(dp(24), dp(20), dp(24), dp(20));
        activityCard.setGravity(Gravity.CENTER);
        activityCard.setBackground(outlineDrawable(accentColor, false));
        activityMessage = dialogueLabel("", 15);
        activityMessage.setGravity(Gravity.CENTER);
        activityCard.addView(activityMessage, matchWrap());
        int activityAnimationHeight = dp(300);
        activityRouteTrace = new RouteTraceView();
        activityRouteTrace.setVisibility(View.GONE);
        LinearLayout.LayoutParams routeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activityAnimationHeight);
        activityCard.addView(activityRouteTrace, routeParams);
        activityHolotape = new HolotapeLoadingView(this, accentColor);
        activityHolotape.setVisibility(View.GONE);
        activityCard.addView(activityHolotape, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activityAnimationHeight));
        activityPlayerDeath = new PlayerDeathView(this, accentColor);
        activityPlayerDeath.setVisibility(View.GONE);
        activityCard.addView(activityPlayerDeath, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activityAnimationHeight));
        activityCellChange = new CellChangeView(this, accentColor);
        activityCellChange.setVisibility(View.GONE);
        activityCard.addView(activityCellChange, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activityAnimationHeight));
        activityCrafting = new CraftingBenchView(this, accentColor);
        activityCrafting.setVisibility(View.GONE);
        activityCard.addView(activityCrafting, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activityAnimationHeight));
        activityContainer = new ContainerView(this, accentColor);
        activityContainer.setVisibility(View.GONE);
        activityCard.addView(activityContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activityAnimationHeight));
        FrameLayout.LayoutParams activityParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        activityOverlay.addView(activityCard, activityParams);
        barterSummary = new BarterSummaryView(this, accentColor);
        barterSummary.setVisibility(View.GONE);
        FrameLayout.LayoutParams barterParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        barterParams.setMargins(dp(16), dp(16), dp(16), dp(16));
        activityOverlay.addView(barterSummary, barterParams);
        lootSummary = new BarterSummaryView(this, accentColor, true);
        lootSummary.setVisibility(View.GONE);
        FrameLayout.LayoutParams lootParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lootParams.setMargins(dp(16), dp(16), dp(16), dp(16));
        activityOverlay.addView(lootSummary, lootParams);
        screen.addView(activityOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gameKeyboard = new GameKeyboardView(this, accentColor, BACKGROUND, code -> {
            if (textEntrySession == 0 || bridgeOutput == null) return;
            playUiSound();
            sendBridgeCommand("TEXTKEY " + textEntrySession + " " + code);
        });
        gameKeyboard.setVisibility(View.GONE);
        screen.addView(gameKeyboard, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
    }

    private TextView dialogueLabel(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(accentColor);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gameKeyboard != null && gameKeyboard.getVisibility() == View.VISIBLE) {
            dialogueSkipGesture = false;
            resetNavigationSwipe();
            return super.dispatchTouchEvent(event);
        }
        if (interactionBlocked) {
            dialogueSkipGesture = false;
            resetNavigationSwipe();
            if (barterSummary != null && barterSummary.isShown()) return super.dispatchTouchEvent(event);
            if (lootSummary != null && lootSummary.isShown()) return super.dispatchTouchEvent(event);
            return true;
        }
        if (handleNavigationSwipe(event)) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dialogueSkipGesture = dialogueActive && !dialogueReady
                    && waitingOverlay != null && waitingOverlay.getVisibility() != View.VISIBLE;
            if (dialogueSkipGesture) {
                dialogueGestureToken = dialogueSpeechToken;
                dialogueTouchX = event.getX();
                dialogueTouchY = event.getY();
                dialogueGestureMoved = false;
            }
        }
        if (!dialogueSkipGesture) return super.dispatchTouchEvent(event);
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        if (Math.abs(event.getX() - dialogueTouchX) > slop
                || Math.abs(event.getY() - dialogueTouchY) > slop
                || event.getPointerCount() > 1) dialogueGestureMoved = true;
        if (action == MotionEvent.ACTION_UP) {
            dialogueSkipGesture = false;
            if (!dialogueGestureMoved && dialogueActive && !dialogueReady
                    && dialogueGestureToken != 0 && dialogueGestureToken == dialogueSpeechToken) {
                playUiSound();
                sendBridgeCommand("DIALOGUESKIP " + dialogueGestureToken);
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            dialogueSkipGesture = false;
        }
        return true;
    }

    private boolean navigationSwipeAllowed() {
        return !interactionBlocked && !dialogueActive && !vatsActive && !navigationAnimating
                && waitingOverlay != null && waitingOverlay.getVisibility() != View.VISIBLE
                && (contentDialog == null || !contentDialog.isShowing())
                && (contentPopup == null || !contentPopup.isShowing())
                && (versionMismatchDialog == null || !versionMismatchDialog.isShowing());
    }

    private boolean navigationHit(View view, MotionEvent event) {
        if (view == null || !view.isShown()) return false;
        view.getLocationOnScreen(navigationHitLocation);
        return event.getRawX() >= navigationHitLocation[0]
                && event.getRawX() < navigationHitLocation[0] + view.getWidth()
                && event.getRawY() >= navigationHitLocation[1]
                && event.getRawY() < navigationHitLocation[1] + view.getHeight();
    }

    private void resetNavigationSwipe() {
        navigationSwipeCandidate = navigationSwipeCaptured = false;
    }

    private void cancelNavigationTouch(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        // Cancel the child's tap/long-press before consuming the rest of a swipe.
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private boolean handleNavigationSwipe(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            resetNavigationSwipe();
            if (!navigationSwipeAllowed()) return false;
            navigationSwipeMain = navigationHit(mainTabs, event);
            if (!navigationSwipeMain && (navigationHit(mapCanvas, event)
                    || navigationHit(localMapCanvas, event) || SUBSECTIONS[selectedSection].length < 2)) return false;
            navigationSwipeCandidate = true;
            navigationSwipeX = event.getX(); navigationSwipeY = event.getY();
            navigationSwipeSection = selectedSection;
            navigationSwipeSubsection = selectedSubsection;
            return false;
        }
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN)
            navigationSwipeCandidate = false;
        if (action == MotionEvent.ACTION_CANCEL) {
            boolean captured = navigationSwipeCaptured;
            resetNavigationSwipe();
            return captured;
        }
        if (!navigationSwipeCaptured && !navigationSwipeAllowed()) {
            resetNavigationSwipe();
            return false;
        }
        float dx = event.getX() - navigationSwipeX, dy = event.getY() - navigationSwipeY;
        if (navigationSwipeCandidate && !navigationSwipeCaptured) {
            int slop = ViewConfiguration.get(this).getScaledTouchSlop();
            if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) navigationSwipeCandidate = false;
            else if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                    && Math.abs(dx) >= Math.max(dp(20), slop * 2) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                cancelNavigationTouch(event);
                navigationSwipeCaptured = true;
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean captured = navigationSwipeCaptured;
            boolean change = captured && navigationSwipeCandidate && navigationSwipeAllowed()
                    && Math.abs(dx) >= dp(48) && Math.abs(dx) > Math.abs(dy) * 1.5f
                    && selectedSection == navigationSwipeSection && selectedSubsection == navigationSwipeSubsection;
            boolean main = navigationSwipeMain;
            resetNavigationSwipe();
            if (change) switchBySwipe(main, dx < 0 ? 1 : -1);
            return captured;
        }
        return navigationSwipeCaptured;
    }

    private void switchBySwipe(boolean main, int direction) {
        int section = selectedSection, subsection = selectedSubsection;
        int nextSection = main ? Math.floorMod(section + direction, MAIN_SECTIONS.length) : section;
        int nextSubsection = main ? 0 : Math.floorMod(subsection + direction, SUBSECTIONS[section].length);
        playUiSound();
        navigateWithShutter(() -> {
            if (interactionBlocked || dialogueActive || vatsActive || waitingOverlay.getVisibility() == View.VISIBLE
                    || selectedSection != section || selectedSubsection != subsection) return;
            selectedSection = nextSection;
            selectedSubsection = nextSubsection;
            if (main) buildSubTabs();
            renderPage();
            if (selectedSubsection < subTabViews.size()) {
                TextView selected = subTabViews.get(selectedSubsection);
                selected.post(() -> selected.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, selected.getWidth(), selected.getHeight()), true));
            }
        });
    }

    private GradientDrawable dialogueChoiceBackground(String text) {
        GradientDrawable background = outlineDrawable(accentColor, false);
        Matcher check = DIALOGUE_CHECK.matcher(text.trim());
        if (check.find()) {
            boolean isStatCheck = false;
            for (String name : SPECIAL_NAMES) if (name.equalsIgnoreCase(check.group(1))) isStatCheck = true;
            for (String name : SKILL_NAMES) if (name.equalsIgnoreCase(check.group(1))) isStatCheck = true;
            if (!isStatCheck) return background;
            boolean failed = check.group(3) != null
                    && Double.parseDouble(check.group(2)) < Double.parseDouble(check.group(3));
            int tint = failed ? Color.rgb(220, 55, 45) : Color.rgb(65, 190, 85);
            background.setColor(blend(BACKGROUND, tint, 0.15f));
        }
        return background;
    }

    private void renderDialogue() {
        if (dialogueOverlay == null) return;
        dialogueOverlay.removeAllViews();
        dialogueOverlay.setVisibility(dialogueActive ? View.VISIBLE : View.GONE);
        if (!dialogueActive) return;
        TextView speaker = dialogueLabel(dialogueSpeaker.isEmpty() ? "DIALOGUE" : dialogueSpeaker, 18);
        speaker.setPadding(0, 0, 0, dp(16));
        dialogueOverlay.addView(speaker, matchWrap());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(outlineDrawable(accentColor, false));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialogueOverlay.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (dialogueChoices.isEmpty()) {
            TextView waiting = dialogueLabel("Listening…", 14);
            waiting.setGravity(Gravity.CENTER);
            rows.addView(waiting, matchWrap());
        }
        for (String[] choice : dialogueChoices) {
            TextView row = dialogueLabel(choice[1], 15);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(52));
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackground(dialogueChoiceBackground(choice[1]));
            row.setAlpha(dialogueReady ? 1f : 0.5f);
            row.setEnabled(dialogueReady);
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(8);
            rows.addView(row, params);
            row.setOnClickListener(view -> {
                playUiSound();
                row.setEnabled(false);
                sendBridgeCommand("DIALOGUESELECT " + choice[0]);
                row.postDelayed(() -> row.setEnabled(dialogueReady), 700);
            });
        }
    }

    private void buildMainTabs() {
        mainTabs.removeAllViews();
        mainTabViews.clear();
        for (int i = 0; i < MAIN_SECTIONS.length; i++) {
            final int section = i;
            NavigationKeyView button = new NavigationKeyView(this);
            button.setText(MAIN_SECTIONS[i]);
            button.setContentDescription(MAIN_SECTIONS[i]);
            LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            keyParams.rightMargin = i == MAIN_SECTIONS.length - 1 ? 0 : dp(6);
            mainTabs.addView(button, keyParams);
            mainTabViews.add(button);
            button.setOnClickListener(view -> {
                playUiSound();
                navigateWithShutter(() -> {
                    selectedSection = section;
                    selectedSubsection = 0;
                    buildSubTabs();
                    renderPage();
                });
            });
        }
        styleNavigation();
    }

    private void buildSubTabs() {
        subTabs.removeAllViews();
        subTabViews.clear();
        subTabs.setGravity(Gravity.CENTER);
        subNavigation.setVisibility(selectedSection == 3 ? View.GONE : View.VISIBLE);
        inventoryWeight.setVisibility(selectedSection == 1 ? View.VISIBLE : View.GONE);
        String[] sections = SUBSECTIONS[selectedSection];
        for (int i = 0; i < sections.length; i++) {
            final int subsection = i;
            TextView tab = tab(sections[i]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            params.rightMargin = dp(3);
            subTabs.addView(tab, params);
            subTabViews.add(tab);
            tab.setOnClickListener(view -> {
                playUiSound();
                navigateWithShutter(() -> {
                    selectedSubsection = subsection;
                    renderPage();
                });
            });
        }
        styleNavigation();
    }

    private void renderPage() {
        if (pageContent == null || dialogueActive || vatsActive || interactionBlocked) {
            return;
        }
        if (mapSearchDialog != null) mapSearchDialog.dismiss();
        if (selectedSection != 2 || selectedSubsection != 0 || localMapSelected) viewedQuestDestination = null;
        if (selectedSection != 2 || selectedSubsection != 1) pendingQuestViewRequest = 0;
        // Drop color-update references to the previous page while preserving the fixed controls.
        while (brightText.size() > fixedBrightTextCount) {
            brightText.remove(brightText.size() - 1);
        }
        while (softText.size() > fixedSoftTextCount) {
            softText.remove(softText.size() - 1);
        }
        if (bridgeOutput != null && !(selectedSection == 2 && selectedSubsection == 0)) {
            sendBridgeCommand("LOCALOFF");
        }
        if (bridgeOutput != null) {
            boolean shouldRequestNotes = selectedSection == 2 && selectedSubsection == 2;
            if (shouldRequestNotes != notesRequested) {
                notesRequested = shouldRequestNotes;
                sendBridgeCommand(shouldRequestNotes ? "NOTESON" : "NOTESOFF");
            }
        }
        activeNotePlaybackRuler = null;
        radioCurrentValue = null;
        radioWaveform = null;
        panelViews.clear();
        inventoryGroupHeaders.clear();
        equippedInventoryRows.clear();
        inventoryActionViews.clear();
        inventoryConditionViews.clear();
        if (inventoryDetailAnimator != null) {
            inventoryDetailAnimator.end();
            inventoryDetailAnimator = null;
        }
        inventoryDetailsViews.clear();
        expandedInventoryView = null;
        if (selectedSection != 1 && expandedInventoryForm != 0) closeInventoryDetails();
        pageContent.removeAllViews();
        healthValue = null;
        actionPointsValue = null;
        healthBar = null;
        actionPointsBar = null;
        statusClock = null;
        statusDate = null;
        statusLevel = null;
        experienceBar = null;
        if (selectedSection == 0) {
            switch (selectedSubsection) {
                case 0: renderVitalsPage(); break;
                case 1: renderSpecialPage(); break;
                case 2: renderSkillPage(); break;
                case 3: renderPerkPage(); break;
                case 4: renderReputationPage(); break;
                default: renderPendingPage("Status data is not available.");
            }
        } else if (selectedSection == 1) {
            renderInventoryPage();
        } else if (selectedSection == 2) {
            switch (selectedSubsection) {
                case 0: renderMapPage(); break;
                case 1: renderQuestPage(); break;
                case 2: renderMiscPage(); break;
                case 3: renderRadioPage(); break;
                case 4: renderGeneralStatsPage(); break;
                case 5: renderChallengesPage(); break;
            }
        } else {
            renderSettingsPage();
        }
        styleNavigation();
    }

    private void renderVitalsPage() {
        LinearLayout calendar = new LinearLayout(this);
        calendar.setGravity(Gravity.CENTER_VERTICAL);
        calendar.setPadding(dp(10), dp(7), dp(10), dp(7));
        calendar.setBackgroundColor(panelColor);
        panelViews.add(calendar);
        LinearLayout.LayoutParams calendarParams = matchWrap();
        calendarParams.bottomMargin = dp(10);
        pageContent.addView(calendar, calendarParams);
        LinearLayout dateColumn = new LinearLayout(this);
        dateColumn.setOrientation(LinearLayout.VERTICAL);
        dateColumn.addView(softLabel("DATE", 9, true), matchWrap());
        statusDate = label("--.--.----", 12, true);
        statusDate.setSingleLine(true);
        statusDate.setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        dateColumn.addView(statusDate, matchWrap());
        calendar.addView(dateColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout experience = new LinearLayout(this);
        experience.setGravity(Gravity.CENTER_VERTICAL);
        statusLevel = label("LVL --", 11, true);
        statusLevel.setSingleLine(true);
        experience.addView(statusLevel, wrap());
        experienceBar = new StatusBarView(this, accentColor);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, dp(6), 1);
        barParams.leftMargin = dp(7);
        experience.addView(experienceBar, barParams);
        LinearLayout.LayoutParams experienceParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f);
        experienceParams.leftMargin = dp(8);
        experienceParams.rightMargin = dp(8);
        calendar.addView(experience, experienceParams);
        LinearLayout timeColumn = new LinearLayout(this);
        timeColumn.setOrientation(LinearLayout.VERTICAL);
        timeColumn.setGravity(Gravity.RIGHT);
        TextView timeCaption = softLabel("TIME", 9, true);
        timeCaption.setGravity(Gravity.RIGHT);
        timeColumn.addView(timeCaption, matchWrap());
        statusClock = label("--:--", 14, true);
        statusClock.setGravity(Gravity.RIGHT);
        timeColumn.addView(statusClock, matchWrap());
        calendar.addView(timeColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updateStatusClock();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams topParams = matchWrap();
        topParams.bottomMargin = dp(12);
        pageContent.addView(top, topParams);

        LinearLayout vitals = new LinearLayout(this);
        vitals.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams vitalsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.43f);
        vitalsParams.rightMargin = dp(10);
        top.addView(vitals, vitalsParams);
        healthValue = addCompactMetric(vitals, "HEALTH", cachedHealth);
        actionPointsValue = addCompactMetric(vitals, "ACTION POINTS", cachedActionPoints);

        LinearLayout pipBoySide = new LinearLayout(this);
        pipBoySide.setOrientation(LinearLayout.VERTICAL);
        top.addView(pipBoySide, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.57f));
        TextView limbHeading = softLabel("LIMB CONDITION", 11, true);
        pipBoySide.addView(limbHeading, matchWrap());
        limbConditionView = new LimbConditionView();
        limbConditionView.setValues(cachedLimbValues);
        pipBoySide.addView(limbConditionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(142)));

        LinearLayout survival = new LinearLayout(this);
        survival.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams survivalParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        survivalParams.topMargin = dp(4);
        pipBoySide.addView(survival, survivalParams);
        addSurvivalMetric(survival, "RADS", cachedEffectValues[0]);
        if (cachedHardcore) {
            addSurvivalMetric(survival, "WATER", cachedEffectValues[1]);
            addSurvivalMetric(survival, "FOOD", cachedEffectValues[2]);
            addSurvivalMetric(survival, "SLEEP", cachedEffectValues[3]);
        }

        pageContent.addView(softLabel("ALL EFFECTS", 12, true), matchWrap());
        renderActiveEffectRows();
        updateVitalDisplays();
    }

    private void updateStatusClock() {
        int split = cachedGameDate.lastIndexOf(' ');
        String date = split > 0 ? cachedGameDate.substring(0, split).trim() : "--.--.----";
        String time = split > 0 ? cachedGameDate.substring(split + 1).trim() : "--:--";
        if (statusDate != null && !date.contentEquals(statusDate.getText())) statusDate.setText(date);
        if (statusClock != null && !time.contentEquals(statusClock.getText())) statusClock.setText(time);
        updateExperienceDisplay();
    }

    private void updateExperienceDisplay() {
        if (statusLevel != null) statusLevel.setText(cachedPlayerLevel > 0 ? "LVL " + cachedPlayerLevel : "LVL --");
        if (experienceBar != null) {
            experienceBar.setValues(cachedLevelExperience, cachedLevelExperienceRequired);
            experienceBar.setContentDescription(cachedPlayerLevel <= 0 ? "Experience unavailable"
                    : cachedLevelCapped ? "Maximum level reached"
                    : "Experience progress toward level " + (cachedPlayerLevel + 1));
        }
    }

    private TextView addCompactMetric(LinearLayout parent, String name, String initialValue) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(9), dp(5), dp(6), dp(5));
        card.setBackgroundColor(panelColor);
        panelViews.add(card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
        params.bottomMargin = dp(6);
        parent.addView(card, params);
        card.addView(softLabel(name, 10, true), matchWrap());
        TextView value = label(initialValue, 14, true);
        value.setSingleLine(true);
        value.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        card.addView(value, matchWrap());
        StatusBarView bar = new StatusBarView(this, accentColor);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        barParams.topMargin = dp(5);
        card.addView(bar, barParams);
        if ("HEALTH".equals(name)) {
            healthBar = bar;
            bar.setHealthWarningEnabled(true);
            card.setOnClickListener(view -> showHealingChoices(false));
        }
        else actionPointsBar = bar;
        return value;
    }

    private void updateVitalDisplays() {
        dismissUnneededHealingPopup();
        if (healthValue != null) healthValue.setText(cachedHealth + " / " + cachedMaxHealth);
        if (actionPointsValue != null) actionPointsValue.setText(cachedActionPoints + " / " + cachedMaxActionPoints);
        if (healthBar != null) healthBar.setValues(cachedHealth, cachedMaxHealth);
        if (actionPointsBar != null) actionPointsBar.setValues(cachedActionPoints, cachedMaxActionPoints);
    }

    private void addSurvivalMetric(LinearLayout parent, String name, String value) {
        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.VERTICAL);
        metric.setGravity(Gravity.CENTER);
        metric.setBackgroundColor(panelColor);
        panelViews.add(metric);
        if ("RADS".equals(name)) metric.setOnClickListener(view -> showHealingChoices(true));
        else if ("WATER".equals(name)) metric.setOnClickListener(view -> showSurvivalChoices(1));
        else if ("FOOD".equals(name)) metric.setOnClickListener(view -> showSurvivalChoices(2));
        else if ("SLEEP".equals(name)) metric.setOnClickListener(view -> showSurvivalChoices(3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        parent.addView(metric, params);
        metric.addView(softLabel(name, 8, true), wrap());
        metric.addView(label(value == null ? "--.-" : value, 12, true), wrap());
    }

    private String specialDisplayValue(int index) {
        return (specialModifiers[index] > 0 ? "(+) "
                : specialModifiers[index] < 0 ? "(-) " : "") + cachedSpecialValues[index];
    }

    private void renderSpecialPage() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowsParams = matchWrap();
        pageContent.addView(rows, rowsParams);
        for (int i = 0; i < SPECIAL_NAMES.length; i++) {
            specialValues[i] = addSpecialRow(rows, i);
            specialValues[i].setText(specialDisplayValue(i));
        }
    }

    private void renderSkillPage() {
        for (int i = 0; i < SKILL_NAMES.length; i++) {
            addDataRow(SKILL_NAMES[i], cachedSkillValues[i] == null ? "--.-" : cachedSkillValues[i]);
        }
    }

    private void renderPerkPage() {
        if (!perksLoaded) {
            renderPendingPage("Waiting for the New Vegas perk list…");
        } else if (perkEntries.isEmpty()) {
            renderPendingPage("No perks acquired yet.");
        } else {
            for (PerkEntry entry : perkEntries) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(9), dp(12), dp(9));
                card.setBackgroundColor(panelColor);
                panelViews.add(card);
                LinearLayout.LayoutParams params = matchWrap();
                params.bottomMargin = dp(4);
                pageContent.addView(card, params);
                TextView title = softLabel(entry.name + (entry.rank > 1 ? " (Rank " + entry.rank + ")" : ""), 12, true);
                card.addView(title, matchWrap());
                if (expandedPerk == entry.formId && entry.formId != 0) {
                    String description = perkDescriptions.get(entry.formId);
                    TextView body = label(description == null ? "Loading description…"
                            : description.isEmpty() ? "No description available." : description, 12, false);
                    body.setPadding(0, dp(10), 0, dp(2));
                    card.addView(body, matchWrap());
                    if (description == null && pendingPerkDetail != entry.formId) {
                        pendingPerkDetail = entry.formId;
                        sendBridgeCommand("PERKDETAIL " + Long.toUnsignedString(entry.formId));
                    }
                }
                if (entry.formId != 0) card.setOnClickListener(view -> {
                    playUiSound();
                    expandedPerk = expandedPerk == entry.formId ? 0 : entry.formId;
                    refreshPerksPage();
                });
            }
        }
    }

    private void refreshPerksPage() {
        if (selectedSection != 0 || selectedSubsection != 3) return;
        ScrollView scroll = pageContent.getParent() instanceof ScrollView ? (ScrollView)pageContent.getParent() : null;
        int position = scroll == null ? 0 : scroll.getScrollY();
        renderPage();
        if (scroll != null) scroll.post(() -> {
            if (selectedSection == 0 && selectedSubsection == 3) scroll.scrollTo(0, position);
        });
    }

    private void renderChallengesPage() {
        if (!challengesLoaded) {
            renderPendingPage("Waiting for challenges…");
            return;
        }
        if (challengeEntries.isEmpty()) {
            renderPendingPage("No challenges available.");
            return;
        }
        for (ChallengeEntry entry : challengeEntries) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackgroundColor(panelColor);
            panelViews.add(card);
            card.setAlpha(entry.completed ? .5f : 1f);
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(5);
            pageContent.addView(card, params);
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.addView(label(entry.name, 12, true),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView progress = label(Math.min(entry.progress, entry.threshold)
                    + " / " + entry.threshold, 11, false);
            progress.setPadding(dp(8), 0, 0, 0);
            heading.addView(progress, wrap());
            card.addView(heading, matchWrap());
            StatusBarView bar = new StatusBarView(this, accentColor);
            bar.setValues(Integer.toString(entry.progress), Integer.toString(entry.threshold));
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            barParams.topMargin = dp(6);
            card.addView(bar, barParams);
            if (entry.completed || entry.recurring) {
                TextView state = softLabel(entry.completed
                        ? (entry.recurring ? "COMPLETED · REPEATABLE" : "COMPLETED")
                        : "REPEATABLE", 9, false);
                state.setPadding(0, dp(5), 0, 0);
                card.addView(state, matchWrap());
            }
            if (expandedChallenge == entry.formId) {
                String description = challengeDescriptions.get(entry.formId);
                TextView body = label(description == null ? "Loading description…"
                        : description.isEmpty() ? "No description available." : description, 12, false);
                body.setPadding(0, dp(10), 0, 0);
                card.addView(body, matchWrap());
                if (description == null && pendingChallengeDetail != entry.formId) {
                    pendingChallengeDetail = entry.formId;
                    sendBridgeCommand("CHALLENGEDETAIL " + Long.toUnsignedString(entry.formId));
                }
            }
            card.setOnClickListener(view -> {
                playUiSound();
                expandedChallenge = expandedChallenge == entry.formId ? 0 : entry.formId;
                renderPage();
            });
        }
    }

    private void renderGeneralStatsPage() {
        if (!generalStatsLoaded || generalStats.isEmpty()) {
            renderPendingPage("Waiting for general statistics…");
            return;
        }
        for (String[] stat : generalStats) addDataRow(stat[0], stat[1]);
    }

    private void renderReputationPage() {
        LinearLayout karmaRow = new LinearLayout(this);
        karmaRow.setGravity(Gravity.CENTER_VERTICAL);
        karmaRow.setPadding(dp(12), dp(12), dp(12), dp(12));
        karmaRow.setMinimumHeight(dp(80));
        int karmaTone = cachedKarmaLevel >= 3 ? 1 : cachedKarmaLevel >= 0 && cachedKarmaLevel <= 1 ? -1 : 0;
        karmaRow.setBackgroundColor(reputationRowColor(karmaTone));
        if (karmaTone == 0) panelViews.add(karmaRow);
        LinearLayout.LayoutParams karmaParams = matchWrap();
        karmaParams.bottomMargin = dp(8);
        pageContent.addView(karmaRow, karmaParams);
        TextView karmaHeading = softLabel("CURRENT KARMA", 12, true);
        karmaRow.addView(karmaHeading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        karmaRow.addView(label(cachedKarmaTitle, 18, true), wrap());

        if (!reputationsLoaded) {
            renderPendingPage("Waiting for faction reputation…");
            return;
        }
        if (reputationEntries.isEmpty()) {
            renderPendingPage("No faction reputations recorded yet.");
            return;
        }
        for (ReputationEntry entry : reputationEntries) {
            int tone = entry.titleCode >= 1 && entry.titleCode <= 3 ? 1
                    : entry.titleCode == 4 || entry.titleCode == 8 || entry.titleCode == 12 ? -1 : 0;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setBackgroundColor(reputationRowColor(tone));
            if (tone == 0) panelViews.add(row);
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(4);
            pageContent.addView(row, params);
            TextView name = softLabel(entry.name, 12, true);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nameParams.rightMargin = dp(8);
            row.addView(name, nameParams);
            TextView standing = label(reputationTitleName(entry.titleCode), 13, true);
            standing.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            standing.setMaxWidth(dp(150));
            row.addView(standing, wrap());
        }
    }

    private int reputationRowColor(int tone) {
        if (tone == 0) return panelColor;
        int tint = tone > 0 ? Color.rgb(65, 190, 85) : Color.rgb(220, 55, 45);
        return blend(BACKGROUND, tint, .15f);
    }

    private static String reputationTitleName(int titleCode) {
        switch (titleCode) {
            case 1: return "ACCEPTED";
            case 2: return "LIKED";
            case 3: return "IDOLIZED";
            case 4: return "SHUNNED";
            case 5: return "MIXED";
            case 6: return "SMILING TROUBLEMAKER";
            case 7: return "GOOD-NATURED RASCAL";
            case 8: return "HATED";
            case 9: return "SNEERING PUNK";
            case 10: return "UNPREDICTABLE";
            case 11: return "DARK HERO";
            case 12: return "VILIFIED";
            case 13: return "MERCIFUL THUG";
            case 14: return "SOFT-HEARTED DEVIL";
            case 15: return "WILD CHILD";
            default: return "NEUTRAL";
        }
    }

    private static String karmaTitleName(int karmaLevel) {
        switch (karmaLevel) {
            case 0: return "VERY EVIL";
            case 1: return "EVIL";
            case 2: return "NEUTRAL";
            case 3: return "GOOD";
            case 4: return "VERY GOOD";
            default: return "UNKNOWN";
        }
    }

    private void renderActiveEffectRows() {
        if(!activeEffectsLoaded) {
            pageContent.addView(softLabel("WAITING FOR EFFECTS",11,false),matchWrap());
        } else if(activeEffectEntries.isEmpty()) {
            pageContent.addView(softLabel("NO ACTIVE EFFECTS",11,false),matchWrap());
        } else {
            for(String[] entry:activeEffectEntries) addActiveEffectRow(entry[0],entry[1]);
        }
    }

    private void addActiveEffectRow(String name, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundColor(panelColor);
        panelViews.add(row);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.bottomMargin = dp(4);
        pageContent.addView(row, rowParams);
        TextView titleView = softLabel(name, 12, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2);
        titleParams.rightMargin = dp(8);
        row.addView(titleView, titleParams);
        TextView detailView = label(detail, 12, true);
        detailView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(detailView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 3));
    }
    private void renderInventoryPage() {
        String category = SUBSECTIONS[1][selectedSubsection];
        if (expandedInventoryForm != 0 && !category.equals(expandedInventoryCategory)) closeInventoryDetails();
        if (!inventoryLoaded) {
            renderPendingPage("Waiting for the New Vegas inventory…");
            return;
        }
        List<InventoryEntry> items = new ArrayList<>();
        for (InventoryEntry entry : inventoryItems) {
            if (category.equals(entry.category)) items.add(entry);
        }
        if (expandedInventoryForm != 0) {
            boolean found = false;
            for (InventoryEntry entry : items) {
                if (isExpandedInventoryItem(entry)) { found = true; break; }
            }
            if (!found) closeInventoryDetails();
        }
        if (items.isEmpty()) {
            renderPendingPage("No items in this category.");
            return;
        }
        if ("AMMO".equals(category)) {
            // Stable sorting keeps the existing name order within each compatibility group.
            items.sort((left, right) -> Boolean.compare(right.ammoApplicable, left.ammoApplicable));
            for (InventoryEntry entry : items) addInventoryRow(pageContent, entry);
            return;
        }
        items.sort((left, right) -> {
            int order = ("WEAPONS".equals(category) || "APPAREL".equals(category))
                    ? Integer.compare(right.condition, left.condition) : 0;
            if (order == 0) order = String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name);
            if (order == 0) order = left.name.compareTo(right.name);
            if (order == 0) order = Long.compareUnsigned(left.formId, right.formId);
            if (order == 0) order = Long.compareUnsigned(left.instanceToken, right.instanceToken);
            return order;
        });
        String[][] groups = INVENTORY_GROUPS[selectedSubsection];
        Map<String, List<InventoryEntry>> grouped = new LinkedHashMap<>();
        for (String[] group : groups) grouped.put(group[0], new ArrayList<>());
        for (InventoryEntry entry : items) {
            List<InventoryEntry> groupItems = grouped.get(entry.group);
            if (groupItems == null) groupItems = grouped.get(groups[groups.length - 1][0]);
            groupItems.add(entry);
        }
        for (String[] group : groups) {
            List<InventoryEntry> groupItems = grouped.get(group[0]);
            if (!groupItems.isEmpty()) addInventoryGroup(category, group[0], group[1], groupItems);
        }
    }

    private void addInventoryGroup(String category, String group, String title,
                                   List<InventoryEntry> items) {
        String preferenceKey = "inventory_collapsed_" + category + "_" + group;
        boolean collapsed = getPreferences(MODE_PRIVATE).getBoolean(preferenceKey, false);
        InventoryGroupHeaderView heading = new InventoryGroupHeaderView(this, title, items.size(), accentColor);
        heading.setCollapsed(collapsed);
        inventoryGroupHeaders.add(heading);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.topMargin = pageContent.getChildCount() == 0 ? 0 : dp(4);
        headingParams.bottomMargin = dp(2);
        pageContent.addView(heading, headingParams);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        pageContent.addView(content, matchWrap());
        for (InventoryEntry entry : items) {
            addInventoryRow(content, entry);
        }
        heading.setOnClickListener(view -> {
            boolean hide = content.getVisibility() == View.VISIBLE;
            content.setVisibility(hide ? View.GONE : View.VISIBLE);
            if (hide) {
                for (InventoryEntry entry : items) {
                    if (isExpandedInventoryItem(entry)) {
                        if (expandedInventoryView != null) expandedInventoryView.setVisibility(View.GONE);
                        closeInventoryDetails();
                        break;
                    }
                }
            }
            getPreferences(MODE_PRIVATE).edit().putBoolean(preferenceKey, hide).apply();
            heading.setCollapsed(hide);
            playUiSound();
        });
    }

    private void addInventoryRow(LinearLayout parent, InventoryEntry entry) {
        boolean aidItem = "AID".equals(entry.category);
        boolean ammoItem = "AMMO".equals(entry.category);
        boolean gearItem = "WEAPONS".equals(entry.category) || "APPAREL".equals(entry.category);
        boolean throwable = "WEAPONS".equals(entry.category) && entry.condition == -2;
        boolean canActivate = aidItem ? entry.usable
                : ammoItem ? !entry.equipped && entry.ammoApplicable : gearItem && entry.instanceToken != 0;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundColor(panelColor);
        if (ammoItem && !entry.ammoApplicable) row.setAlpha(0.38f);
        if (entry.equipped) {
            row.setBackground(equippedInventoryBackground());
            equippedInventoryRows.add(row);
        }
        row.setClickable(true);
        panelViews.add(row);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.bottomMargin = dp(4);
        parent.addView(row, rowParams);

        TextView titleView = softLabel(entry.name + (throwable ? " (" + entry.count + ")" : ""), 12, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.rightMargin = dp(8);
        if (gearItem) {
            LinearLayout description = new LinearLayout(this);
            description.setOrientation(LinearLayout.VERTICAL);
            row.addView(description, titleParams);
            description.addView(titleView, matchWrap());
            if (!throwable) {
                InventoryConditionView condition = new InventoryConditionView(this, entry.condition,
                        entry.modFlags != 0, entry.hotkey, accentColor);
                inventoryConditionViews.add(condition);
                LinearLayout.LayoutParams conditionParams = matchWrap();
                conditionParams.topMargin = dp(4);
                description.addView(condition, conditionParams);
            } else if (entry.hotkey > 0) {
                description.addView(softLabel("HOTKEY " + entry.hotkey, 9, true), matchWrap());
            }
        } else {
            row.addView(titleView, titleParams);
        }
        String stateText;
        if (aidItem) {
            stateText = "× " + entry.count + (entry.usable ? "  ·  USE" : "  ·  INGREDIENT");
        } else if (ammoItem) {
            stateText = entry.equipped ? "● EQUIPPED" : "× " + entry.count + "  ·  SELECT";
        } else if (gearItem) {
            stateText = entry.equipped ? "● UNEQUIP" : "EQUIP";
        } else {
            stateText = "× " + entry.count;
        }
        TextView stateView = label(stateText, 11, true);
        stateView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (canActivate) {
            stateView.setPadding(dp(8), dp(5), dp(8), dp(5));
            stateView.setBackgroundColor(blend(BACKGROUND, accentColor, 0.22f));
            inventoryActionViews.add(stateView);
        }
        if (entry.equipped) stateView.setTextColor(accentColor);
        else if (aidItem && !entry.usable) stateView.setTextColor(secondaryColor);
        row.addView(stateView, wrap());
        if (canActivate) {
            stateView.setOnClickListener(view -> {
                playUiSound();
                if (aidItem) {
                    sendBridgeCommand("USE " + Long.toUnsignedString(entry.formId));
                } else if (ammoItem) {
                    sendBridgeCommand("EQUIP " + Long.toUnsignedString(entry.formId) + " 0");
                } else {
                    if (isExpandedInventoryItem(entry)) {
                        pendingExpandedEquip = entry;
                        expandedEquipStartedAt = android.os.SystemClock.uptimeMillis();
                        expandedEquipPreviousTokens.clear();
                        for (InventoryEntry owned : inventoryItems) {
                            if (owned.formId == entry.formId) expandedEquipPreviousTokens.add(owned.instanceToken);
                        }
                    }
                    sendBridgeCommand("EQUIPCOPY " + Long.toUnsignedString(entry.formId) + " "
                            + Long.toUnsignedString(entry.instanceToken) + " "
                            + (entry.equipped ? "1" : "0"));
                }
            });
        } else {
            // Disabled action areas must not bubble a tap into the row's details toggle.
            stateView.setOnClickListener(view -> { });
        }
        InventoryDetailsView details = new InventoryDetailsView(this, accentColor);
        details.setDropAction(() -> dropInventoryItem(entry));
        if ("WEAPONS".equals(entry.category)) details.setModAction(() -> showInventoryMods(entry));
        if ((gearItem && entry.instanceToken != 0 && entry.condition != 0) || (aidItem && entry.usable))
            details.setHotkeyAction(() -> showInventoryHotkeys(entry));
        inventoryDetailsViews.add(details);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.bottomMargin = dp(4);
        details.setVisibility(View.GONE);
        parent.addView(details, detailParams);
        if (isExpandedInventoryItem(entry)) {
            expandedInventoryView = details;
            details.setDetails(cachedInventoryDetails.isEmpty() ? "Loading item details…"
                    : cachedInventoryDetails, cachedInventoryCanMod, cachedInventoryCanDrop);
            details.setVisibility(View.VISIBLE);
        }
        row.setOnClickListener(view -> {
            playUiSound();
            if (isExpandedInventoryItem(entry)) {
                closeInventoryDetails();
                animateInventoryDetails(details, false);
            } else {
                if (expandedInventoryView != null) animateInventoryDetails(expandedInventoryView, false);
                clearExpandedEquipFollow();
                expandedInventoryCategory = entry.category;
                expandedInventoryForm = entry.formId;
                expandedInventoryToken = entry.instanceToken;
                cachedInventoryDetails = "";
                cachedInventoryCanMod = false;
                cachedInventoryCanDrop = false;
                expandedInventoryView = details;
                details.setDetails("Loading item details…", false, false);
                animateInventoryDetails(details, true);
                sendBridgeCommand("INSPECT " + Long.toUnsignedString(entry.formId) + " "
                        + Long.toUnsignedString(entry.instanceToken));
            }
        });
    }

    private boolean inventoryActionAllowed() {
        return !interactionBlocked && !dialogueActive && !vatsActive && bridgeOutput != null;
    }

    private TextView inventoryPopupLabel(String text, int size, boolean bold) {
        TextView result = label(text, size, bold);
        brightText.remove(result);
        return result;
    }

    private LinearLayout inventoryPopup(String heading, boolean overview) {
        if (contentDialog != null) contentDialog.dismiss();
        inventoryOverviewList = null;
        healingChoices = null;
        survivalNeed = -1;
        Dialog dialog = new Dialog(this);
        contentDialog = dialog;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(12));
        card.setBackground(outlineDrawable(accentColor, false));
        TextView title = inventoryPopupLabel(heading, 13, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        card.addView(title, matchWrap());
        if (overview) {
            LinearLayout sorts = new LinearLayout(this);
            String[] labels = {"NAME", "WEIGHT", "VALUE"};
            for (int i = 0; i < labels.length; ++i) {
                final int sort = i;
                TextView button = dialogAction(labels[i]);
                inventorySortButtons[i] = button;
                sorts.addView(button, new LinearLayout.LayoutParams(0, dp(36), 1));
                button.setOnClickListener(view -> {
                    playUiSound(); inventoryOverviewSort = sort; refreshInventoryOverview();
                });
            }
            card.addView(sorts, matchWrap());
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, overview
                ? Math.min(dp(320), getResources().getDisplayMetrics().heightPixels / 2) : -2));
        if (overview) inventoryOverviewList = rows;
        else healingChoices = rows;
        TextView close = dialogAction("CLOSE");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(36));
        closeParams.topMargin = dp(10);
        card.addView(close, closeParams);
        close.setOnClickListener(view -> { playUiSound(); dialog.dismiss(); });
        dialog.setOnDismissListener(ignored -> {
            if (contentDialog == dialog) {
                contentDialog = null;
                inventoryOverviewList = null;
                healingChoices = null;
                survivalNeed = -1;
            }
        });
        dialog.setContentView(card);
        if (dialog.getWindow() != null)
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(400)), -2);
        }
        return rows;
    }

    private void showInventoryOverview() {
        if (!inventoryActionAllowed()) return;
        playUiSound();
        inventoryPopup("INVENTORY", true);
        refreshInventoryOverview();
    }

    private void refreshInventoryOverview() {
        LinearLayout parent = inventoryOverviewList;
        if (parent == null) return;
        int scrollY = ((ScrollView) parent.getParent()).getScrollY();
        parent.removeAllViews();
        for (int i = 0; i < inventorySortButtons.length; ++i) if (inventorySortButtons[i] != null)
            inventorySortButtons[i].setBackground(outlineDrawable(accentColor, i == inventoryOverviewSort));
        List<InventoryEntry> sorted = new ArrayList<>(inventoryItems);
        sorted.sort((a, b) -> {
            int result = inventoryOverviewSort == 1 ? Double.compare(b.weight * b.count, a.weight * a.count)
                    : inventoryOverviewSort == 2 ? Double.compare(b.value * b.count, a.value * a.count) : 0;
            if (result == 0) result = a.name.compareToIgnoreCase(b.name);
            if (result == 0) result = Long.compare(a.formId, b.formId);
            if (result == 0) result = Long.compare(a.instanceToken, b.instanceToken);
            return result;
        });
        if (sorted.isEmpty()) parent.addView(inventoryPopupLabel(inventoryLoaded ? "No items." : "Loading inventory…", 12, false));
        for (InventoryEntry entry : sorted) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackgroundColor(panelColor);
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.topMargin = dp(4);
            parent.addView(row, rowParams);
            LinearLayout description = new LinearLayout(this);
            description.setOrientation(LinearLayout.VERTICAL);
            row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
            description.addView(inventoryPopupLabel(entry.name + (entry.count > 1 ? " × " + entry.count : ""), 12, true));
            description.addView(inventoryPopupLabel(String.format(Locale.ROOT, "WG %.2f   VAL %.0f",
                    entry.weight * entry.count, entry.value * entry.count), 10, false));
            TextView drop = inventoryPopupLabel("DROP", 10, true);
            drop.setGravity(Gravity.CENTER);
            drop.setPadding(dp(10), dp(8), dp(10), dp(8));
            drop.setBackgroundColor(blend(BACKGROUND, accentColor, 0.22f));
            drop.setEnabled(entry.canDrop);
            drop.setAlpha(entry.canDrop ? 1f : 0.35f);
            drop.setContentDescription(entry.canDrop ? "Drop " + entry.name : "Cannot drop " + entry.name);
            row.addView(drop, wrap());
            drop.setOnClickListener(view -> dropInventoryItem(entry));
        }
        parent.post(() -> { if (inventoryOverviewList == parent)
            ((ScrollView) parent.getParent()).scrollTo(0, scrollY); });
    }

    private boolean healingNeeded(boolean radiation) {
        try {
            if (radiation) {
                float rads = Float.parseFloat(cachedEffectValues[0]);
                return Float.isFinite(rads) && rads > 0;
            }
            float hp = Float.parseFloat(cachedHealth), maximum = Float.parseFloat(cachedMaxHealth);
            return Float.isFinite(hp) && Float.isFinite(maximum) && hp >= 0 && maximum > 0 && hp < maximum;
        } catch (NumberFormatException ignored) { return false; }
    }

    private void dismissUnneededHealingPopup() {
        if (healingChoices != null && !(survivalNeed >= 0 ? survivalNeeded(survivalNeed)
                : healingNeeded(healingRadiation)) && contentDialog != null)
            contentDialog.dismiss();
    }

    private void showHealingChoices(boolean radiation) {
        if (!inventoryActionAllowed() || !healingNeeded(radiation)) return;
        playUiSound();
        healingRadiation = radiation;
        inventoryPopup(radiation ? "REDUCE RADIATION" : "RESTORE HEALTH", false);
        refreshHealingChoices();
    }

    private void refreshHealingChoices() {
        if (healingChoices == null) return;
        if (survivalNeed >= 0) { refreshSurvivalChoices(); return; }
        healingChoices.removeAllViews();
        boolean available = false;
        for (int type = healingRadiation ? 3 : 1; type <= (healingRadiation ? 3 : 2); ++type) {
            InventoryEntry medicine = null;
            for (InventoryEntry entry : inventoryItems)
                if (entry.medicine == type && entry.usable && entry.count > 0) { medicine = entry; break; }
            int count = medicine == null ? 0 : medicine.count;
            available |= count > 0;
            final long form = medicine == null ? 0 : medicine.formId;
            TextView use = inventoryPopupLabel((type == 3 ? "RADAWAY" : type == 1 ? "STIMPAK" : "SUPER STIMPAK")
                    + " × " + count + "   USE", 12, true);
            use.setEnabled(count > 0);
            use.setAlpha(count > 0 ? 1f : 0.35f);
            use.setGravity(Gravity.CENTER);
            use.setPadding(dp(10), dp(12), dp(10), dp(12));
            use.setBackgroundColor(blend(BACKGROUND, accentColor, 0.22f));
            LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(6);
            healingChoices.addView(use, params);
            use.setOnClickListener(view -> {
                if (!inventoryActionAllowed() || !healingNeeded(healingRadiation)) return;
                for (InventoryEntry owned : inventoryItems) if (owned.formId == form && owned.count > 0 && owned.usable) {
                    playUiSound();
                    if (contentDialog != null) contentDialog.dismiss();
                    sendBridgeCommand("USE " + Long.toUnsignedString(form));
                    break;
                }
            });
        }
        if (!available) healingChoices.addView(inventoryPopupLabel(healingRadiation ? "No RadAway available" : "No Stimpaks available", 12, false), matchWrap());
    }

    private boolean survivalNeeded(int need) {
        if (!cachedHardcore || (need < 1 || need > 3)) return false;
        try {
            float value = Float.parseFloat(cachedEffectValues[need]);
            return Float.isFinite(value) && value > 0;
        } catch (NumberFormatException ignored) { return false; }
    }

    private String needReduction(InventoryEntry entry, int need) {
        return need == 1 ? entry.waterRelief : need == 2 ? entry.foodRelief : entry.sleepRelief;
    }

    private void showSurvivalChoices(int need) {
        if (!inventoryActionAllowed() || !survivalNeeded(need)) return;
        playUiSound();
        inventoryPopup(need == 1 ? "REDUCE THIRST" : need == 2 ? "REDUCE HUNGER" : "REDUCE SLEEP DEPRIVATION", false);
        survivalNeed = need;
        ScrollView scroll = (ScrollView) healingChoices.getParent();
        ViewGroup.LayoutParams params = scroll.getLayoutParams();
        params.height = Math.min(dp(260), getResources().getDisplayMetrics().heightPixels / 2);
        scroll.setLayoutParams(params);
        refreshSurvivalChoices();
    }

    private void refreshSurvivalChoices() {
        if (healingChoices == null || survivalNeed < 0) return;
        if (!survivalNeeded(survivalNeed)) { dismissUnneededHealingPopup(); return; }
        healingChoices.removeAllViews();
        java.util.ArrayList<InventoryEntry> choices = new java.util.ArrayList<>();
        final int need = survivalNeed;
        for (InventoryEntry entry : inventoryItems)
            if ("AID".equals(entry.category) && entry.usable && entry.count > 0
                    && !needReduction(entry, need).isEmpty()) choices.add(entry);
        choices.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        for (InventoryEntry entry : choices) {
            TextView use = inventoryPopupLabel(entry.name + " × " + entry.count + "\n"
                    + (need == 1 ? "WATER " : need == 2 ? "FOOD " : "SLEEP ") + needReduction(entry, need) + "   USE", 12, true);
            use.setPadding(dp(10), dp(10), dp(10), dp(10));
            use.setBackgroundColor(blend(BACKGROUND, accentColor, 0.22f));
            LinearLayout.LayoutParams params = matchWrap(); params.topMargin = dp(6);
            healingChoices.addView(use, params);
            use.setOnClickListener(view -> {
                if (!inventoryActionAllowed() || !survivalNeeded(need)) return;
                for (InventoryEntry owned : inventoryItems) {
                    if (owned.formId != entry.formId || owned.count <= 0 || !owned.usable
                            || needReduction(owned, need).isEmpty()) continue;
                    playUiSound();
                    if (contentDialog != null) contentDialog.dismiss();
                    sendBridgeCommand("USENEED " + Long.toUnsignedString(owned.formId) + " " + need);
                    break;
                }
            });
        }
        if (choices.isEmpty()) healingChoices.addView(inventoryPopupLabel(need == 1
                ? "No carried items reduce thirst." : need == 2 ? "No carried items reduce hunger."
                : "No carried items reduce sleep deprivation.", 12, false), matchWrap());
    }

    private TextView dialogAction(String text) {
        TextView action = label(text, 10, true);
        action.setGravity(Gravity.CENTER);
        action.setBackground(outlineDrawable(accentColor, false));
        action.setClickable(true);
        action.setFocusable(true);
        return action;
    }

    private void dropInventoryItem(InventoryEntry entry) {
        if (interactionBlocked || bridgeOutput == null || dropItemPending
                || !entry.canDrop) return;
        playUiSound();
        boolean individualGear = "APPAREL".equals(entry.category)
                || ("WEAPONS".equals(entry.category) && entry.condition != -2);
        if (entry.count > 1 && !individualGear) {
            showDropQuantity(entry);
            return;
        }
        submitInventoryDrop(entry, 1);
    }

    private void submitInventoryDrop(InventoryEntry entry, int quantity) {
        if (interactionBlocked || dialogueActive || vatsActive || bridgeOutput == null || dropItemPending
                || !entry.canDrop) return;
        InventoryEntry latest = null;
        for (InventoryEntry owned : inventoryItems) {
            if (owned.formId == entry.formId && owned.instanceToken == entry.instanceToken
                    && owned.category.equals(entry.category)) { latest = owned; break; }
        }
        if (latest == null || !latest.canDrop || quantity < 1 || quantity > latest.count) {
            Log.w("FNVDS", "Inventory quantity changed. Open the item details again.");
            return;
        }
        dropItemPending = true;
        sendBridgeCommand("DROPITEM " + Long.toUnsignedString(entry.formId) + " "
                + Long.toUnsignedString(entry.instanceToken) + " " + quantity);
    }

    private void showDropQuantity(InventoryEntry entry) {
        if (contentDialog != null) contentDialog.dismiss();
        inventoryOverviewList = null;
        healingChoices = null;
        Dialog dialog = new Dialog(this);
        contentDialog = dialog;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(outlineDrawable(accentColor, false));
        TextView title = label("DROP ITEMS", 13, true);
        title.setGravity(Gravity.CENTER); card.addView(title, matchWrap());
        TextView name = softLabel(entry.name, 12, true);
        name.setGravity(Gravity.CENTER); name.setPadding(0, dp(10), 0, dp(10));
        card.addView(name, matchWrap());
        TextView amount = label("", 18, true);
        amount.setGravity(Gravity.CENTER);
        card.addView(amount, new LinearLayout.LayoutParams(-1, dp(38)));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = dialogAction("−");
        controls.addView(minus, new LinearLayout.LayoutParams(dp(38), dp(38)));
        SeekBar slider = new SeekBar(this);
        slider.setMin(1); slider.setMax(entry.count); slider.setProgress(1);
        slider.setContentDescription("Quantity to drop");
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accentColor));
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accentColor));
        slider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(secondaryColor));
        controls.addView(slider, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView plus = dialogAction("+");
        controls.addView(plus, new LinearLayout.LayoutParams(dp(38), dp(38)));
        card.addView(controls, matchWrap());
        Runnable updateAmount = () -> amount.setText(String.format(Locale.ROOT, "%d / %d", slider.getProgress(), entry.count));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { updateAmount.run(); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { playUiSound(); }
        });
        updateAmount.run();
        minus.setOnClickListener(view -> { playUiSound(); slider.setProgress(Math.max(1, slider.getProgress() - 1)); });
        plus.setOnClickListener(view -> {
            playUiSound();
            if (slider.getProgress() < slider.getMax()) slider.setProgress(slider.getProgress() + 1);
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(12), 0, 0);
        TextView cancel = dialogAction("CANCEL"), drop = dialogAction("DROP");
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(38), 1);
        cancelParams.rightMargin = dp(8);
        actions.addView(cancel, cancelParams);
        actions.addView(drop, new LinearLayout.LayoutParams(0, dp(38), 1));
        cancel.setOnClickListener(view -> { playUiSound(); dialog.dismiss(); });
        drop.setOnClickListener(view -> {
            playUiSound();
            dialog.dismiss();
            submitInventoryDrop(entry, slider.getProgress());
        });
        card.addView(actions, matchWrap());
        dialog.setOnDismissListener(ignored -> { if (contentDialog == dialog) contentDialog = null; });
        dialog.setContentView(card);
        if (dialog.getWindow() != null)
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(360)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showInventoryHotkeys(InventoryEntry entry) {
        if (interactionBlocked || bridgeOutput == null) return;
        playUiSound();
        if (contentDialog != null) contentDialog.dismiss();
        Dialog dialog = new Dialog(this);
        contentDialog = dialog;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(10));
        card.setBackground(outlineDrawable(accentColor, false));
        TextView heading = label("ASSIGN HOTKEY", 13, true);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading, matchWrap());
        TextView name = softLabel(entry.name, 11, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(6), 0, dp(6));
        card.addView(name, matchWrap());
        HotkeyPickerView picker = new HotkeyPickerView(this, accentColor, slot -> {
            if (interactionBlocked) return;
            playUiSound();
            sendBridgeCommand("HOTKEY " + Long.toUnsignedString(entry.formId) + " "
                    + Long.toUnsignedString(entry.instanceToken) + " " + slot);
            dialog.dismiss();
        });
        activeHotkeyPicker = picker;
        updateHotkeyPicker();
        card.addView(picker, matchWrap());
        TextView cancel = dialogAction("CANCEL");
        card.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (activeHotkeyPicker == picker) activeHotkeyPicker = null;
            if (contentDialog == dialog) contentDialog = null;
        });
        dialog.setContentView(card);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32),
                    dp(380)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void updateHotkeyPicker() {
        if (activeHotkeyPicker == null) return;
        String[] names = new String[8];
        for (Map.Entry<Integer, String> entry : hotkeyNames.entrySet()) names[entry.getKey() - 1] = entry.getValue();
        activeHotkeyPicker.updateNames(names);
    }

    private void showInventoryMods(InventoryEntry entry) {
        if (interactionBlocked || bridgeOutput == null) return;
        playUiSound();
        if (contentDialog != null) contentDialog.dismiss();
        Dialog dialog = new Dialog(this);
        contentDialog = dialog;
        activeModWeapon = entry;
        modInstallPending = false;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(12));
        card.setBackground(outlineDrawable(accentColor, false));
        TextView heading = label("WEAPON MODS", 13, true);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading, matchWrap());
        TextView name = label(entry.name, 12, true);
        name.setPadding(0, dp(10), 0, dp(8));
        card.addView(name, matchWrap());
        ScrollView scroll = new ScrollView(this);
        activeModList = new LinearLayout(this);
        activeModList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(activeModList, matchWrap());
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView warning = softLabel("Installed mods cannot be removed.", 10, false);
        warning.setPadding(0, dp(8), 0, dp(10));
        card.addView(warning, matchWrap());
        TextView cancel = dialogAction("CANCEL");
        card.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (contentDialog == dialog) {
                activeModList = null;
                activeModWeapon = null;
                modInstallPending = false;
                contentDialog = null;
            }
        });
        updateWeaponModMenu();
        dialog.setContentView(card);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(440)),
                    Math.min(getResources().getDisplayMetrics().heightPixels - dp(48), dp(350)));
        }
    }

    private void updateWeaponModMenu() {
        if (activeModList == null || activeModWeapon == null) return;
        activeModList.removeAllViews();
        if (modInstallPending) {
            activeModList.addView(label("INSTALLING…", 11, true), matchWrap());
            return;
        }
        if (activeModWeapon.formId != weaponModsForm || activeModWeapon.instanceToken != weaponModsToken) {
            activeModList.addView(label("Loading compatible mods…", 11, false), matchWrap());
            return;
        }
        for (WeaponModEntry mod : weaponMods) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.setBackground(outlineDrawable(accentColor, false));
            TextView title = label(mod.name() + (mod.installed() ? "\nINSTALLED" : "\nOWNED × " + mod.count()), 11, true);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (!mod.installed() && mod.count() > 0) {
                TextView install = dialogAction("INSTALL");
                install.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.addView(install, wrap());
                install.setOnClickListener(view -> {
                    if (interactionBlocked || modInstallPending || activeModWeapon == null) return;
                    playUiSound();
                    modInstallPending = true;
                    sendBridgeCommand("INSTALLMOD " + Long.toUnsignedString(activeModWeapon.formId) + " "
                            + Long.toUnsignedString(activeModWeapon.instanceToken) + " "
                            + Long.toUnsignedString(mod.formId()));
                    updateWeaponModMenu();
                });
            } else row.setAlpha(.5f);
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(6);
            activeModList.addView(row, params);
        }
        if (weaponMods.isEmpty()) activeModList.addView(label("No compatible mods.", 11, false), matchWrap());
    }

    private boolean isExpandedInventoryItem(InventoryEntry entry) {
        return expandedInventoryForm == entry.formId && expandedInventoryToken == entry.instanceToken
                && expandedInventoryCategory.equals(entry.category);
    }

    private void closeInventoryDetails() {
        clearExpandedEquipFollow();
        expandedInventoryForm = 0;
        expandedInventoryToken = 0;
        expandedInventoryCategory = "";
        expandedInventoryView = null;
        cachedInventoryDetails = "";
        cachedInventoryCanMod = false;
        cachedInventoryCanDrop = false;
        if (bridgeOutput != null) sendBridgeCommand("INSPECT 0 0");
    }

    private void clearExpandedEquipFollow() {
        pendingExpandedEquip = null;
        expandedEquipPreviousTokens.clear();
    }

    private void followExpandedEquip(List<InventoryEntry> latest) {
        InventoryEntry before = pendingExpandedEquip;
        if (before == null) return;
        if (expandedInventoryForm != before.formId || !expandedInventoryCategory.equals(before.category)
                || android.os.SystemClock.uptimeMillis() - expandedEquipStartedAt > 5000) {
            clearExpandedEquipFollow();
            return;
        }
        InventoryEntry changed = null, matching = null;
        int changedCount = 0, matchingCount = 0;
        for (InventoryEntry entry : latest) {
            if (entry.formId != before.formId || !entry.category.equals(before.category)
                    || entry.instanceToken == 0 || expandedEquipPreviousTokens.contains(entry.instanceToken)
                    || entry.condition != before.condition || entry.modFlags != before.modFlags
                    || entry.hotkey != before.hotkey) continue;
            matching = entry;
            ++matchingCount;
            if (entry.equipped != before.equipped) { changed = entry; ++changedCount; }
        }
        // Equip is queued by the engine. Follow an unambiguous reissued copy
        // while waiting for its equipped state; never pick between duplicates.
        InventoryEntry replacement = changedCount == 1 ? changed : matchingCount == 1 ? matching : null;
        if (replacement == null) return;
        if (expandedInventoryToken != replacement.instanceToken) {
            expandedInventoryToken = replacement.instanceToken;
            sendBridgeCommand("INSPECT " + Long.toUnsignedString(replacement.formId) + " "
                    + Long.toUnsignedString(replacement.instanceToken));
        }
        if (replacement.equipped != before.equipped) clearExpandedEquipFollow();
    }

    private void animateInventoryDetails(InventoryDetailsView view, boolean open) {
        if (inventoryDetailAnimator != null) {
            inventoryDetailAnimator.end();
            inventoryDetailAnimator = null;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        int from = view.getVisibility() == View.VISIBLE ? view.getHeight() : 0;
        int width = ((View)view.getParent()).getWidth();
        view.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int to = open ? view.getMeasuredHeight() : 0;
        view.setVisibility(View.VISIBLE);
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        inventoryDetailAnimator = animator;
        animator.setDuration(180);
        animator.addUpdateListener(animation -> {
            params.height = (int) animation.getAnimatedValue();
            view.setLayoutParams(params);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setLayoutParams(params);
                if (!open) view.setVisibility(View.GONE);
                if (inventoryDetailAnimator == animator) inventoryDetailAnimator = null;
            }
        });
        animator.start();
    }

    private void renderQuestPage() {
        if (!questsLoaded) {
            renderPendingPage("Waiting for active quest data…");
            return;
        }
        if (questEntries.isEmpty()) {
            renderPendingPage("No active quest objectives.");
            return;
        }
        for (QuestEntry entry : questEntries) {
            addQuestRow(entry);
        }
    }

    private void addQuestRow(QuestEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundColor(panelColor);
        boolean canTrack = entry.questFormId != 0 && !entry.tracked;
        row.setClickable(canTrack);
        panelViews.add(row);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.bottomMargin = dp(4);
        pageContent.addView(row, rowParams);

        LinearLayout description = new LinearLayout(this);
        description.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        descriptionParams.rightMargin = dp(8);
        row.addView(description, descriptionParams);
        description.addView(softLabel(entry.quest, 12, true), matchWrap());
        if (!entry.objective.isEmpty()) {
            description.addView(softLabel(entry.objective, 10, false), matchWrap());
        }
        if (!entry.optionalObjectives.isEmpty()) {
            description.addView(softLabel("OPTIONAL", 8, true), matchWrap());
            for (String objective : entry.optionalObjectives) {
                description.addView(softLabel("• " + objective, 9, false), matchWrap());
            }
        }

        TextView stateView = label(entry.tracked ? "● TRACKED" : "TRACK", 11, true);
        stateView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        stateView.setTextColor(entry.tracked ? accentColor : secondaryColor);
        row.addView(stateView, wrap());
        if (canTrack) {
            row.setOnClickListener(view -> sendBridgeCommand(
                    "TRACKQUEST " + Long.toUnsignedString(entry.questFormId)));
        }
        if (entry.questFormId != 0) {
            row.setOnLongClickListener(view -> {
                playUiSound();
                showQuestMarkerPrompt(entry, "View the quest marker for " + entry.quest + "?", true);
                return true;
            });
        }
    }

    private void showQuestMarkerPrompt(QuestEntry entry, String message, boolean confirm) {
        if (contentDialog != null) contentDialog.dismiss();
        Dialog dialog = new Dialog(this);
        contentDialog = dialog;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(outlineDrawable(accentColor, false));
        TextView title = label("QUEST MARKER", 13, true);
        title.setGravity(Gravity.CENTER); card.addView(title, matchWrap());
        TextView text = softLabel(message, 11, false);
        text.setGravity(Gravity.CENTER); text.setPadding(0, dp(12), 0, dp(16));
        card.addView(text, matchWrap());
        LinearLayout actions = new LinearLayout(this);
        TextView cancel = dialogAction(confirm ? "CANCEL" : "CLOSE");
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(38), 1));
        cancel.setOnClickListener(view -> dialog.dismiss());
        if (confirm) {
            TextView show = dialogAction("VIEW MARKER");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            params.leftMargin = dp(8); actions.addView(show, params);
            show.setOnClickListener(view -> {
                dialog.dismiss();
                if (interactionBlocked || dialogueActive || vatsActive || bridgeOutput == null) return;
                playUiSound();
                questViewSequence = (questViewSequence + 1) & 0xffffffffL;
                if (questViewSequence == 0) questViewSequence = 1;
                long request = questViewSequence;
                pendingQuestViewRequest = request;
                pendingQuestViewForm = entry.questFormId;
                sendBridgeCommand("VIEWQUEST " + Long.toUnsignedString(entry.questFormId) + " " + request);
                pageContent.postDelayed(() -> {
                    if (pendingQuestViewRequest != request) return;
                    pendingQuestViewRequest = 0;
                    if (selectedSection == 2 && selectedSubsection == 1 && !interactionBlocked)
                        showQuestMarkerPrompt(null, "The quest marker could not be retrieved. Please try again.", false);
                }, 8000);
            });
        }
        card.addView(actions, matchWrap());
        dialog.setOnDismissListener(ignored -> { if (contentDialog == dialog) contentDialog = null; });
        dialog.setContentView(card); dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(360)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void renderMapPage() {
        LinearLayout options = new LinearLayout(this);
        options.setGravity(Gravity.CENTER);
        for (String name : new String[]{"LOCAL", "WORLD"}) {
            boolean local = "LOCAL".equals(name);
            TextView button = tab(name);
            button.setBackground(outlineDrawable(accentColor, local == localMapSelected));
            button.setOnClickListener(view -> {
                localMapSelected = local;
                renderPage();
            });
            options.addView(button, new LinearLayout.LayoutParams(0, dp(36), 1));
        }
        if (!localMapSelected) {
            View bookmark = new View(this) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    paint.setColor(accentColor);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    float cx=getWidth()/2f, cy=getHeight()/2f;
                    Path path=new Path();
                    path.moveTo(cx-dp(6),cy-dp(9));
                    path.lineTo(cx+dp(6),cy-dp(9));
                    path.lineTo(cx+dp(6),cy+dp(9));
                    path.lineTo(cx,cy+dp(4));
                    path.lineTo(cx-dp(6),cy+dp(9));
                    path.close();
                    canvas.drawPath(path,paint);
                }
            };
            bookmark.setContentDescription("Map favourites");
            bookmark.setBackground(outlineDrawable(accentColor,false));
            bookmark.setOnClickListener(view -> {
                if(mapCanvas!=null) mapCanvas.showFavourites(view);
            });
            options.addView(bookmark,new LinearLayout.LayoutParams(dp(44),dp(36)));
            View search = new View(this) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    paint.setColor(accentColor);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    float cx = getWidth()/2f, cy = getHeight()/2f;
                    canvas.drawCircle(cx-dp(2), cy-dp(2), dp(6), paint);
                    canvas.drawLine(cx+dp(2), cy+dp(2), cx+dp(8), cy+dp(8), paint);
                }
            };
            search.setContentDescription("Search map locations");
            search.setBackground(outlineDrawable(accentColor, false));
            search.setOnClickListener(view -> {
                playUiSound();
                if (mapCanvas != null) mapCanvas.showLocationSearch();
            });
            options.addView(search, new LinearLayout.LayoutParams(dp(44), dp(36)));
        }
        pageContent.addView(options, matchWrap());
        if (localMapSelected) {
            if (bridgeOutput != null) sendBridgeCommand("LOCALON");
            localMapCanvas = new LocalMapCanvas();
            localMapCanvas.setMinimumHeight(dp(180));
            pageContent.addView(localMapCanvas, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            if (bridgeOutput != null) sendBridgeCommand("LOCALOFF");
            mapCanvas = new MapCanvas();
            mapCanvas.setMinimumHeight(dp(180));
            pageContent.addView(mapCanvas, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
    }

    private void updateMapSearchLocations() {
        if (mapSearchDialog == null) return;
        List<MapSearchDialog.Location> locations = new ArrayList<>();
        for (MapMarkerEntry marker : mapMarkers) {
            // The bridge sends only visible/known or discovered map-marker references.
            if (marker.refId == 0 || marker.name == null || marker.name.trim().isEmpty()
                    || !Float.isFinite(marker.x) || !Float.isFinite(marker.y)
                    || marker.x < 0 || marker.x > 1 || marker.y < 0 || marker.y > 1) continue;
            locations.add(new MapSearchDialog.Location(marker.refId, marker.name, marker.x, marker.y, marker.canTravel));
        }
        mapSearchDialog.updateLocations(locations);
    }

    private void selectWorldMap(String worldName) {
        String normalized = worldName == null ? "" : worldName.toLowerCase(java.util.Locale.ROOT);
        String compact = normalized.replaceAll("[^a-z]", "");
        String nextWorldKey = compact.contains("sierramadre") ? "DEAD_MONEY"
                : compact.contains("zion") ? "HONEST_HEARTS"
                : compact.contains("bigmt") || compact.contains("bigmountain")
                        || compact.contains("oldworldblues")
                        ? "OLD_WORLD_BLUES"
                : compact.contains("divide") || compact.contains("lonesomeroad")
                        ? "LONESOME_ROAD" : "MOJAVE";
        if (nextWorldKey.equals(currentWorldKey)) {
            if (mapBitmap == null) requestGameMap(nextWorldKey);
            return;
        }
        currentWorldKey = nextWorldKey;
        if (mapSearchDialog != null) mapSearchDialog.dismiss();
        mapBitmap = streamedMapCache.get(nextWorldKey);
        mapLoadStatus = mapBitmap == null ? "REQUESTING MAP FROM GAME" : "";
        if (mapCanvas != null) {
            mapCanvas.resetForMapChange();
            mapCanvas.invalidate();
        }
        if (mapBitmap == null) requestGameMap(nextWorldKey);
    }

    private void requestGameMap(String worldKey) {
        long now = android.os.SystemClock.elapsedRealtime();
        // Suppress duplicate requests while a transfer is pending and throttle retries for the same key.
        if (mapRequestPending && worldKey.equals(requestedMapKey)) return;
        if (worldKey.equals(requestedMapKey) && now - lastMapRequestAt < 10000) return;
        requestedMapKey = worldKey;
        lastMapRequestAt = now;
        mapRequestPending = true;
        mapLoadStatus = "REQUESTING MAP FROM GAME";
        if (mapCanvas != null) mapCanvas.invalidate();
        sendBridgeCommand("GETMAP " + worldKey);
    }

    private Bitmap decodeMapImage(InputStream stream) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        byte[] image = bytes.toByteArray();
        return isDds(image) ? decodeDds(image)
                : BitmapFactory.decodeByteArray(image, 0, image.length);
    }

    private Bitmap removeDefaultWorldMapGrid(Bitmap source) {
        if (source == null || source.getWidth() != 1024 || source.getHeight() != 1024) {
            return source;
        }
  
        int[] verticalLines = {65, 118, 171, 225, 278, 331, 385, 438, 491,
                544, 596, 651, 704, 757, 810, 863, 917, 970};
        int[] horizontalLines = {63, 117, 170, 224, 277, 331, 385, 439, 492,
                546, 599, 653, 707, 760, 814, 868, 921, 975};
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int x : verticalLines) {
            for (int y = 1; y < height - 1; y++) {
                int left = pixels[y * width + x - 1];
                int right = pixels[y * width + x + 1];
                pixels[y * width + x] = averageColor(left, right);
            }
        }
        for (int y : horizontalLines) {
            for (int x = 0; x < width; x++) {
                int above = pixels[(y - 1) * width + x];
                int below = pixels[(y + 1) * width + x];
                pixels[y * width + x] = averageColor(above, below);
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private int averageColor(int first, int second) {
        int alpha = (((first >>> 24) & 0xFF) + ((second >>> 24) & 0xFF)) / 2;
        int red = (((first >>> 16) & 0xFF) + ((second >>> 16) & 0xFF)) / 2;
        int green = (((first >>> 8) & 0xFF) + ((second >>> 8) & 0xFF)) / 2;
        int blue = ((first & 0xFF) + (second & 0xFF)) / 2;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private boolean isDds(byte[] bytes) {
        return bytes.length >= 128 && bytes[0] == 'D' && bytes[1] == 'D'
                && bytes[2] == 'S' && bytes[3] == ' ';
    }

    private Bitmap decodeDds(byte[] bytes) {
        int height = readLeInt(bytes, 12);
        int width = readLeInt(bytes, 16);
        int fourCc = readLeInt(bytes, 84);
        int dxt1 = fourCc('D', 'X', 'T', '1');
        int dxt3 = fourCc('D', 'X', 'T', '3');
        int dxt5 = fourCc('D', 'X', 'T', '5');
        // Bound dimensions before allocating the output pixel array.
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null;
        if (fourCc == 0 && readLeInt(bytes, 88) == 32
                && readLeInt(bytes,92) == 0x00FF0000 && readLeInt(bytes,96) == 0x0000FF00
                && readLeInt(bytes,100) == 0x000000FF) {
            int pitch=readLeInt(bytes,20);
            if(pitch < width*4 || (long)128+(long)pitch*height>bytes.length) return null;
            int[] pixels=new int[width*height];
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                pixels[y*width+x]=readLeInt(bytes,128+y*pitch+x*4) | 0xFF000000;
            }
            return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        }
        int bytesPerBlock = fourCc == dxt1 ? 8 : 16;
        if (fourCc != dxt1 && fourCc != dxt3 && fourCc != dxt5) return null;
        int blocksWide = (width + 3) / 4;
        int blocksHigh = (height + 3) / 4;
        if ((long) 128 + (long) blocksWide * blocksHigh * bytesPerBlock > bytes.length) return null;
        int[] pixels = new int[width * height];
        int cursor = 128;
        for (int by = 0; by < blocksHigh; by++) {
            for (int bx = 0; bx < blocksWide; bx++) {
                int colorOffset = cursor + (fourCc == dxt1 ? 0 : 8);
                int color0 = readLeShort(bytes, colorOffset);
                int color1 = readLeShort(bytes, colorOffset + 2);
                int[] colors = dxtColors(color0, color1, fourCc == dxt1);
                long colorIndices = readLeInt(bytes, colorOffset + 4) & 0xFFFFFFFFL;
                long alphaBits = 0;
                int[] alphaTable = null;
                if (fourCc == dxt3) {
                    alphaBits = readLeLong(bytes, cursor);
                } else if (fourCc == dxt5) {
                    alphaTable = dxt5AlphaTable(bytes[cursor] & 0xFF, bytes[cursor + 1] & 0xFF);
                    alphaBits = readLe48(bytes, cursor + 2);
                }
                for (int py = 0; py < 4; py++) {
                    for (int px = 0; px < 4; px++) {
                        int x = bx * 4 + px;
                        int y = by * 4 + py;
                        if (x >= width || y >= height) continue;
                        int color = colors[(int) (colorIndices & 3)];
                        colorIndices >>>= 2;
                        int alpha = 255;
                        if (fourCc == dxt3) {
                            alpha = (int) ((alphaBits & 15) * 17);
                            alphaBits >>>= 4;
                        } else if (fourCc == dxt5) {
                            alpha = alphaTable[(int) (alphaBits & 7)];
                            alphaBits >>>= 3;
                        }
                        pixels[y * width + x] = (color & 0x00FFFFFF) | (alpha << 24);
                    }
                }
                cursor += bytesPerBlock;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private int[] dxtColors(int color0, int color1, boolean allowTransparency) {
        int first = rgb565(color0);
        int second = rgb565(color1);
        int[] colors = new int[4];
        colors[0] = first;
        colors[1] = second;
        if (!allowTransparency || color0 > color1) {
            colors[2] = blendRgb(first, second, 2, 1, 3);
            colors[3] = blendRgb(first, second, 1, 2, 3);
        } else {
            colors[2] = blendRgb(first, second, 1, 1, 2);
            colors[3] = 0;
        }
        return colors;
    }

    private int rgb565(int value) {
        int red = ((value >>> 11) & 31) * 255 / 31;
        int green = ((value >>> 5) & 63) * 255 / 63;
        int blue = (value & 31) * 255 / 31;
        return Color.rgb(red, green, blue);
    }

    private int blendRgb(int first, int second, int firstWeight, int secondWeight, int divisor) {
        int red = (Color.red(first) * firstWeight + Color.red(second) * secondWeight) / divisor;
        int green = (Color.green(first) * firstWeight + Color.green(second) * secondWeight) / divisor;
        int blue = (Color.blue(first) * firstWeight + Color.blue(second) * secondWeight) / divisor;
        return Color.rgb(red, green, blue);
    }

    private int[] dxt5AlphaTable(int alpha0, int alpha1) {
        int[] table = new int[8];
        table[0] = alpha0;
        table[1] = alpha1;
        if (alpha0 > alpha1) {
            for (int i = 1; i <= 6; i++) table[i + 1] = ((7 - i) * alpha0 + i * alpha1) / 7;
        } else {
            for (int i = 1; i <= 4; i++) table[i + 1] = ((5 - i) * alpha0 + i * alpha1) / 5;
            table[6] = 0;
            table[7] = 255;
        }
        return table;
    }

    private int readLeInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private int readLeShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private long readLeLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = (value << 8) | (bytes[offset + i] & 0xFFL);
        return value;
    }

    private long readLe48(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 5; i >= 0; i--) value = (value << 8) | (bytes[offset + i] & 0xFFL);
        return value;
    }

    private int fourCc(char first, char second, char third, char fourth) {
        return first | (second << 8) | (third << 16) | (fourth << 24);
    }

    private void renderRadioPage() {
        renderedRadioStation = cachedRadioStation;
        LinearLayout current = new LinearLayout(this);
        current.setOrientation(LinearLayout.HORIZONTAL);
        current.setGravity(Gravity.CENTER_VERTICAL);
        current.setMinimumHeight(dp(112));
        current.setPadding(dp(12), dp(12), dp(12), dp(12));
        current.setBackgroundColor(panelColor);
        panelViews.add(current);
        LinearLayout.LayoutParams currentParams = matchWrap();
        currentParams.bottomMargin = dp(4);
        pageContent.addView(current, currentParams);
        LinearLayout stationInfo = new LinearLayout(this);
        stationInfo.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, .62f);
        infoParams.rightMargin = dp(14);
        current.addView(stationInfo, infoParams);
        stationInfo.addView(softLabel("CURRENT STATION", 10, true), matchWrap());
        radioCurrentValue = label(cachedRadioStation, 22, true);
        radioCurrentValue.setGravity(Gravity.LEFT);
        radioCurrentValue.setPadding(0, dp(8), 0, 0);
        stationInfo.addView(radioCurrentValue, matchWrap());
        radioWaveform = new RadioWaveformView(this, accentColor);
        radioWaveform.setVisibility(cachedRadioPlaying ? View.VISIBLE : View.INVISIBLE);
        current.addView(radioWaveform, new LinearLayout.LayoutParams(0, dp(70), .38f));

        if (radioStations.isEmpty()) {
            TextView empty = softLabel("NO DISCOVERED STATIONS", 12, false);
            empty.setPadding(dp(12), dp(14), dp(12), dp(14));
            pageContent.addView(empty, matchWrap());
        }
        for (RadioStation station : radioStations) {
            boolean isCurrent = station.name.equalsIgnoreCase(cachedRadioStation);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setBackgroundColor(panelColor);
            row.setClickable(station.refId != 0);
            panelViews.add(row);
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.bottomMargin = dp(4);
            pageContent.addView(row, rowParams);

            TextView stationName = label((isCurrent ? "• " : "") + station.name, 12, true);
            row.addView(stationName, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView stationState = label(isCurrent ? "TURN OFF" : "TUNE", 10, true);
            stationState.setGravity(Gravity.CENTER);
            stationState.setPadding(dp(8), dp(5), dp(8), dp(5));
            stationState.setBackgroundColor(blend(BACKGROUND, accentColor, 0.22f));
            inventoryActionViews.add(stationState);
            row.addView(stationState, wrap());
            if (station.refId != 0) {
                View.OnClickListener tune = view ->
                        sendBridgeCommand("TUNERADIO " + Long.toUnsignedString(station.refId));
                row.setOnClickListener(tune);
                stationState.setOnClickListener(tune);
            }
        }
    }

    private void renderMiscPage() {
        // Existing note rows take precedence over the loader, even while more notes are arriving.
        if (noteEntries.isEmpty()) {
            if (!notesLoaded || noteScanActive) {
                LinearLayout loading = new LinearLayout(this);
                loading.setOrientation(LinearLayout.VERTICAL);
                loading.setGravity(Gravity.CENTER);
                loading.addView(new VaultDoorLoadingView(),
                        new LinearLayout.LayoutParams(dp(56), dp(56)));
                TextView message = softLabel("Loading notes", 14, false);
                message.setGravity(Gravity.CENTER);
                message.setTextColor(accentColor);
                LinearLayout.LayoutParams messageParams = wrap();
                messageParams.topMargin = dp(12);
                loading.addView(message, messageParams);
                pageContent.addView(loading, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            } else {
                renderPendingPage("No notes collected.");
            }
            return;
        }
        for (NoteEntry entry : noteEntries) addNoteRow(entry);
    }

    private void addNoteRow(NoteEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundColor(panelColor);
        panelViews.add(row);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.bottomMargin = dp(4);
        pageContent.addView(row, rowParams);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(heading, matchWrap());
        boolean audioNote = entry.type == 0 || entry.type == 3;
        TextView title = softLabel(entry.name, 12, true);
        title.setAlpha(entry.read ? 0.48f : 1f);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView action = label(audioNote
                ? (playingNoteFormId == entry.formId ? "STOP" : "LISTEN")
                : entry.read ? "READ" : "OPEN", 10, true);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), dp(6), dp(10), dp(6));
        action.setTextColor(accentColor);
        action.setBackgroundColor(blend(BACKGROUND, accentColor, 0.22f));
        inventoryActionViews.add(action);
        heading.addView(action, wrap());
        action.setOnClickListener(view -> {
            if (audioNote) {
                if (playingNoteFormId == entry.formId) {
                    sendBridgeCommand("STOPNOTE");
                    playingNoteFormId = 0;
                    playingNoteDurationMs = 0;
                    playingNoteElapsedMs = 0;
                } else {
                    if (playingNoteFormId != 0) sendBridgeCommand("STOPNOTE");
                    sendBridgeCommand("PLAYNOTE " + Long.toUnsignedString(entry.formId));
                    playingNoteFormId = entry.formId;
                    playingNoteDurationMs = 0;
                    playingNoteElapsedMs = 0;
                    entry.read = true;
                }
                renderPage();
            } else if (isTextNote(entry)) {
                openTextNote(entry);
            } else {
                entry.read = true;
                sendBridgeCommand("READNOTE " + Long.toUnsignedString(entry.formId));
                Log.w("FNVDS", "IMAGE NOTE CONTENT IS NOT AVAILABLE");
                renderPage();
            }
        });

        if (audioNote) {
            TextView kind = softLabel(entry.type == 3 ? "VOICE NOTE" : "SOUND NOTE", 9, false);
            kind.setAlpha(entry.read ? 0.48f : 1f);
            row.addView(kind, matchWrap());
            if (playingNoteFormId == entry.formId) {
                NotePlaybackRulerView ruler = new NotePlaybackRulerView(
                        playingNoteDurationMs, playingNoteElapsedMs);
                activeNotePlaybackRuler = ruler;
                LinearLayout.LayoutParams rulerParams = matchWrap();
                rulerParams.topMargin = dp(7);
                row.addView(ruler, rulerParams);
            }
        } else {
            boolean textNote = isTextNote(entry);
            TextView kind = softLabel(textNote ? "TEXT NOTE" : "IMAGE NOTE",
                    9, false);
            kind.setAlpha(entry.read ? 0.48f : 1f);
            row.addView(kind, matchWrap());
            if (textNote) {
                row.setClickable(true);
                row.setOnClickListener(view -> openTextNote(entry));
            }
        }
    }

    private boolean isTextNote(NoteEntry entry) {
        // New Vegas exposes text notes as type 1 in its note-type API; some
        // records report type 2. Use the actual payload to disambiguate those
        // records instead of treating every type 1/2 note as an image.
        return entry.type == 1 || (entry.type == 2 && !entry.content.trim().isEmpty());
    }

    private void openTextNote(NoteEntry entry) {
        entry.read = true;
        sendBridgeCommand("READNOTE " + Long.toUnsignedString(entry.formId));
        Dialog dialog = new Dialog(this);
        contentDialog = dialog;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(outlineDrawable(accentColor, false));

        TextView title = label(entry.name, 13, true);
        title.setGravity(Gravity.CENTER);
        card.addView(title, matchWrap());

        ScrollView noteScroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = matchWrap();
        scrollParams.topMargin = dp(10);
        scrollParams.bottomMargin = 0;
        TextView content = label(entry.content.isEmpty()
                ? "No text is stored in this note." : entry.content, 13, false);
        content.setTextColor(secondaryColor);
        content.setPadding(dp(4), dp(8), dp(4), dp(8));
        noteScroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(noteScroll, scrollParams);

        dialog.setContentView(card);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32),
                    dp(360));
            int height = Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.72f),
                    dp(560));
            dialog.getWindow().setLayout(width, height);
        }
        dialog.setOnDismissListener(ignored -> renderPage());
        renderPage();
    }

    private void renderSettingsPage() {
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams choicesParams = matchWrap();
        pageContent.addView(choices, choicesParams);
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.bottomMargin = dp(8);
            choices.addView(row, rowParams);
            for (int column = 0; column < 2; column++) {
                final int colorIndex = rowIndex * 2 + column;
                boolean selected = accentColor == PIPBOY_COLOR_VALUES[colorIndex];
                TextView choice = tab(PIPBOY_COLORS[colorIndex]);
                choice.setTextSize(12);
                choice.setTextColor(selected ? accentColor : secondaryColor);
                choice.setBackground(outlineDrawable(PIPBOY_COLOR_VALUES[colorIndex], selected));
                choice.setMinHeight(dp(44));
                LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(0,
                        dp(44), 1);
                if (column > 0) choiceParams.leftMargin = dp(8);
                row.addView(choice, choiceParams);
                choice.setOnClickListener(view -> {
                    int color = PIPBOY_COLOR_VALUES[colorIndex];
                    getPreferences(MODE_PRIVATE).edit().putInt("pipboy_color", color).apply();
                    applyThemeColor(color);
                });
            }
        }

        addSettingToggle("APP INTERACTION AUDIO", audioEnabled, enabled -> {
            audioEnabled=enabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("audio_enabled",enabled).apply();
            playUiSound();
            renderPage();
        });
        addSettingToggle("SHUTTER PAGE TRANSITIONS", shutterTransitionsEnabled, enabled -> {
            shutterTransitionsEnabled=enabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("transitions_enabled",enabled).apply();
            playUiSound();
            renderPage();
        });
        TextView consoleButton = tab("OPEN GAME CONSOLE");
        consoleButton.setMinHeight(dp(40));
        consoleButton.setBackgroundColor(panelColor);
        panelViews.add(consoleButton);
        LinearLayout.LayoutParams consoleParams = matchWrap();
        consoleParams.topMargin = dp(8);
        pageContent.addView(consoleButton, consoleParams);
        consoleButton.setOnClickListener(view -> {
            playUiSound();
            sendBridgeCommand("CONSOLEOPEN");
        });
        TextView version=softLabel("FNVDS VERSION  " + BuildConfig.VERSION_NAME,11,true);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionParams=matchWrap();
        versionParams.topMargin=dp(14);
        pageContent.addView(version,versionParams);
    }

    private interface ToggleChanged { void changed(boolean enabled); }

    private void addSettingToggle(String title, boolean enabled, ToggleChanged changed) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundColor(panelColor);
        panelViews.add(row);
        row.addView(label(title, 12, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = label(enabled ? "ON" : "OFF", 10, true);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(8), dp(5), dp(8), dp(5));
        state.setBackgroundColor(blend(BACKGROUND, accentColor, 0.22f));
        inventoryActionViews.add(state);
        row.addView(state, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(4);
        pageContent.addView(row, params);
        View.OnClickListener toggle = view -> { playUiSound(); changed.changed(!enabled); };
        row.setOnClickListener(toggle);
        state.setOnClickListener(toggle);
    }

    private void playUiSound() {
        if(!audioEnabled || uiTone==null) return;
        uiTone.startTone(ToneGenerator.TONE_PROP_BEEP2,55);
    }

    private void navigateWithShutter(Runnable changePage) {
        if(!shutterTransitionsEnabled || pageFrame==null || navigationAnimating) {
            changePage.run();return;
        }
        navigationAnimating=true;
        View upper=new View(this), lower=new View(this);
        upper.setBackgroundColor(Color.BLACK);lower.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams topParams=new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,0,Gravity.TOP);
        FrameLayout.LayoutParams bottomParams=new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,0,Gravity.BOTTOM);
        pageFrame.addView(upper,topParams);pageFrame.addView(lower,bottomParams);
        int half=Math.max(1,pageFrame.getHeight()/2);
        ValueAnimator close=ValueAnimator.ofInt(0,half);
        close.setDuration(110);
        close.addUpdateListener(a->{int h=(int)a.getAnimatedValue();topParams.height=h;bottomParams.height=h;upper.setLayoutParams(topParams);lower.setLayoutParams(bottomParams);});
        close.addListener(new android.animation.AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(android.animation.Animator animation){
                changePage.run();
                ValueAnimator open=ValueAnimator.ofInt(half,0);open.setDuration(140);
                open.addUpdateListener(a->{int h=(int)a.getAnimatedValue();topParams.height=h;bottomParams.height=h;upper.setLayoutParams(topParams);lower.setLayoutParams(bottomParams);});
                open.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){pageFrame.removeView(upper);pageFrame.removeView(lower);navigationAnimating=false;}});
                open.start();
            }
        });
        close.start();
    }
    private void renderPendingPage(String messageText) {
        TextView message = softLabel(messageText, 14, false);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(18), dp(36), dp(18), dp(36));
        message.setBackgroundColor(panelColor);
        panelViews.add(message);
        pageContent.addView(message, matchWrap());
    }

    private void addDataRow(String title, String value) {
        addDataRow(pageContent, title, value);
    }

    private void addDataRow(LinearLayout parent, String title, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundColor(panelColor);
        panelViews.add(row);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.bottomMargin = dp(4);
        parent.addView(row, rowParams);
        TextView titleView = softLabel(title, 12, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.rightMargin = dp(8);
        row.addView(titleView, titleParams);
        TextView valueView = label(value, 13, true);
        valueView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(valueView, wrap());
    }

    private TextView addSpecialRow(LinearLayout parent, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(4), dp(14), dp(4));
        row.setBackgroundColor(panelColor);
        panelViews.add(row);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.bottomMargin = dp(4);
        parent.addView(row, rowParams);
        TextView initial = label(SPECIAL_INITIALS[index], 16, true);
        initial.setGravity(Gravity.CENTER);
        row.addView(initial, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView name = softLabel(SPECIAL_NAMES[index], 12, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nameParams.leftMargin = dp(8);
        row.addView(name, nameParams);
        TextView value = label("--.-", 17, true);
        value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        value.setSingleLine(true);
        row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        return value;
    }

    private TextView tab(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(secondaryColor);
        view.setTextSize(11);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        view.setBackgroundColor(panelColor);
        view.setClickable(true);
        view.setFocusable(false);
        return view;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(accentColor);
        view.setTextSize(size);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        brightText.add(view);
        return view;
    }

    private TextView softLabel(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(secondaryColor);
        view.setTextSize(size);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        softText.add(view);
        return view;
    }

    private void styleNavigation() {
        for (int i = 0; i < mainTabViews.size(); i++) {
            boolean selected = i == selectedSection;
            mainTabViews.get(i).setStyle(selected, accentColor, BACKGROUND);
        }
        for (int i = 0; i < subTabViews.size(); i++) {
            boolean selected = i == selectedSubsection;
            TextView tab = subTabViews.get(i);
            tab.setTextColor(selected ? accentColor : blend(BACKGROUND, accentColor, .75f));
            if (selected) {
                GradientDrawable line = new GradientDrawable();
                line.setColor(accentColor);
                android.graphics.drawable.LayerDrawable underline = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), line});
                underline.setLayerHeight(1, dp(2));
                underline.setLayerGravity(1, Gravity.BOTTOM);
                tab.setBackground(underline);
            } else tab.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && limbConditionView != null) limbConditionView.invalidate();
    }

    private void applyThemeColor(int color) {
        accentColor = color;
        secondaryColor = blend(color, Color.WHITE, 0.22f);
        panelColor = blend(BACKGROUND, color, 0.12f);
        if (gameKeyboard != null) gameKeyboard.setAccentColor(color);
        if (inventoryWeight != null) inventoryWeight.setBackgroundColor(panelColor);
        if (radioWaveform != null) radioWaveform.setAccentColor(color);
        if (healthBar != null) healthBar.setAccentColor(color);
        if (actionPointsBar != null) actionPointsBar.setAccentColor(color);
        if (experienceBar != null) experienceBar.setAccentColor(color);
        for (TextView view : brightText) {
            view.setTextColor(accentColor);
        }
        for (TextView view : softText) {
            view.setTextColor(secondaryColor);
        }
        for (View view : panelViews) {
            view.setBackgroundColor(panelColor);
        }
        for (InventoryGroupHeaderView heading : inventoryGroupHeaders) heading.setAccentColor(accentColor);
        for (InventoryConditionView condition : inventoryConditionViews) condition.setAccentColor(accentColor);
        for (InventoryDetailsView details : inventoryDetailsViews) details.setAccentColor(accentColor);
        for (View row : equippedInventoryRows) row.setBackground(equippedInventoryBackground());
        for (TextView action : inventoryActionViews) {
            action.setBackgroundColor(blend(BACKGROUND, accentColor, 0.22f));
        }
        if (pageFrame != null) {
            pageFrame.setBackground(outlineDrawable(accentColor, false));
        }
        if (terminalIntro != null) terminalIntro.setAccentColor(accentColor);
        if (vatsOverlay != null) vatsOverlay.setAccentColor(accentColor);
        if (activityCard != null) {
            styleActivityCard();
            activityMessage.setTextColor(accentColor);
            if (activityRouteTrace != null) activityRouteTrace.updateColor();
            if (activityHolotape != null) activityHolotape.setAccentColor(accentColor);
            if (activityPlayerDeath != null) activityPlayerDeath.setAccentColor(accentColor);
            if (activityCellChange != null) activityCellChange.setAccentColor(accentColor);
            if (activityCrafting != null) activityCrafting.setAccentColor(accentColor);
            if (barterSummary != null) barterSummary.setAccentColor(accentColor);
            if (lootSummary != null) lootSummary.setAccentColor(accentColor);
            if (activityContainer != null) activityContainer.setAccentColor(accentColor);
        }
        if (dialogueActive) renderDialogue();
        styleNavigation();
        if (mapCanvas != null) mapCanvas.invalidate();
        if (selectedSection == 3 && pageContent != null) renderPage();
    }

    private GradientDrawable equippedInventoryBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(panelColor);
        drawable.setStroke(dp(1), blend(BACKGROUND, accentColor, 0.7f));
        return drawable;
    }


    private GradientDrawable outlineDrawable(int color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(selected ? blend(BACKGROUND, color, 0.15f) : BACKGROUND);
        drawable.setStroke(dp(selected ? 2 : 1), color);
        return drawable;
    }

    private int blend(int base, int overlay, float amount) {
        int red = Math.round(Color.red(base) * (1 - amount) + Color.red(overlay) * amount);
        int green = Math.round(Color.green(base) * (1 - amount) + Color.green(overlay) * amount);
        int blue = Math.round(Color.blue(base) * (1 - amount) + Color.blue(overlay) * amount);
        return Color.rgb(red, green, blue);
    }

    private void startBridge() {
        running = true;
        Thread worker = new Thread(this::serve, "FNVDS-Local-Bridge");
        worker.setDaemon(true);
        worker.start();
    }

    private void serve() {
        try (ServerSocket server = new ServerSocket(
                BRIDGE_PORT, 1, InetAddress.getByName("127.0.0.1"))) {
            serverSocket = server;
            showConnection("WAITING FOR NEW VEGAS");
            while (running) {
                try (Socket client = server.accept()) {
                    activeSocket = client;
                    handleClient(client);
                } catch (SocketException closed) {
                    if (running) {
                        showConnection("LINK INTERRUPTED");
                    }
                } catch (IOException error) {
                    if (running) {
                        showConnection("LINK ERROR");
                    }
                } finally {
                    activeSocket = null;
                    bridgeOutput = null;
                    runOnUiThread(() -> {
                        dropItemPending = false;
                        lastDropResultSequence = 0;
                    });
                    notesRequested = false;
                }
            }
        } catch (IOException error) {
            if (running) {
                showConnection("BRIDGE FAILED");
            }
        }
    }

    private void handleClient(Socket client) throws IOException {
        client.setSoTimeout(5000);
        BufferedReader input = new BufferedReader(new InputStreamReader(
                client.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                client.getOutputStream(), StandardCharsets.UTF_8));
        String hello = input.readLine();
        // Publish the command writer only after both handshake messages have been validated.
        if (!"HELLO FNVDS/1".equals(hello)) {
            showConnection("UNKNOWN CLIENT");
            return;
        }
        output.write("PING FNVDS/1\n");
        output.flush();
        String pong = input.readLine();
        if (!"PONG FNVDS/1".equals(pong)) {
            showConnection("HANDSHAKE FAILED");
            return;
        }
        client.setSoTimeout(0);
        bridgeOutput = output;
        if (localMapSelected && selectedSection == 2 && selectedSubsection == 0) {
            sendBridgeCommand("LOCALON");
        }
        if (selectedSection == 2 && selectedSubsection == 2) {
            notesRequested = true;
            sendBridgeCommand("NOTESON");
        }
        showConnection("NEW VEGAS LINKED");
        String line;
        while (running && (line = input.readLine()) != null) {
            processGameState(line);
        }
        if (running) {
            showConnection("WAITING FOR NEW VEGAS");
        }
    }

    private void sendBridgeCommand(String command) {
        bridgeCommandExecutor.execute(() -> {
            BufferedWriter output = bridgeOutput;
            long keyboardSession = textEntrySession;
            boolean keyboardCommand = keyboardSession != 0
                    && command.startsWith("TEXTKEY " + keyboardSession + " ");
            if (command.startsWith("TEXTKEY ") && !keyboardCommand) return;
            // Naming pauses ordinary companion actions, but its active keyboard must still send input.
            if (interactionBlocked && !keyboardCommand
                    && !"LOCALOFF".equals(command) && !"NOTESOFF".equals(command)) return;
            if (output == null) {
                Log.w("FNVDS", "WAITING FOR NEW VEGAS");
                return;
            }
            try {
                synchronized (bridgeWriteLock) {
                    // A reconnect may have replaced the writer captured by this queued task.
                    if (bridgeOutput != output) return;
                    output.write(command);
                    output.newLine();
                    output.flush();
                }
            } catch (IOException error) {
                Log.w("FNVDS", "ITEM COMMAND FAILED");
            }
        });
    }

    private void processGameState(String line) {
        // Preserve empty trailing quest fields, including an empty optional-objective payload.
        String[] fields = line.split(" ", -1);
        try {
            if (fields.length == 19 && "STATE".equals(fields[0]) && "VATS".equals(fields[1])
                    && "BEGIN".equals(fields[2])) {
                VatsView.State state = new VatsView.State(Long.parseUnsignedLong(fields[3]), "1".equals(fields[4]),
                        Long.parseUnsignedLong(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
                        Float.parseFloat(fields[8]), Float.parseFloat(fields[9]), Float.parseFloat(fields[10]),
                        Float.parseFloat(fields[11]), Float.parseFloat(fields[12]), Float.parseFloat(fields[13]),
                        Float.parseFloat(fields[14]), Integer.parseInt(fields[15]), decodeText(fields[16]),
                        decodeText(fields[17]), decodeText(fields[18]), List.of());
                runOnUiThread(() -> { incomingVatsState = state; incomingVatsParts.clear(); });
            } else if (fields.length == 9 && "STATE".equals(fields[0]) && "VATSPART".equals(fields[1])) {
                VatsView.Part part = new VatsView.Part(Long.parseUnsignedLong(fields[2]), Integer.parseInt(fields[3]),
                        Integer.parseInt(fields[4]), Integer.parseInt(fields[5]), "1".equals(fields[6]),
                        "1".equals(fields[7]), decodeText(fields[8]));
                runOnUiThread(() -> incomingVatsParts.add(part));
            } else if (fields.length == 3 && "STATE".equals(fields[0]) && "VATS".equals(fields[1])) {
                boolean closed = "CLOSED".equals(fields[2]);
                boolean end = "END".equals(fields[2]);
                runOnUiThread(() -> {
                    if (closed) {
                        boolean wasActive = vatsActive;
                        vatsActive = false; vatsState = null; incomingVatsState = null; incomingVatsParts.clear();
                        vatsOverlay.setVisibility(View.GONE);
                        if (wasActive) renderPage();
                    } else if (end && incomingVatsState != null) {
                        var s = incomingVatsState;
                        vatsState = new VatsView.State(s.session(), s.ready(), s.targetId(), s.targetIndex(), s.targets(),
                                s.ap(), s.maxAp(), s.hp(), s.maxHp(), s.damage(), s.queuedDamage(), s.cost(), s.queued(),
                                s.target(), s.weapon(), s.ammo(), List.copyOf(incomingVatsParts));
                        if (!vatsActive) {
                            if (contentDialog != null) contentDialog.dismiss();
                            if (contentPopup != null) contentPopup.dismiss();
                            sendBridgeCommand("LOCALOFF");
                            if (notesRequested) { notesRequested = false; sendBridgeCommand("NOTESOFF"); }
                        }
                        vatsActive = true;
                        vatsOverlay.update(vatsState);
                        vatsOverlay.setVisibility(View.VISIBLE);
                    }
                });
            } else if (fields.length == 4 && "STATE".equals(fields[0])
                    && "LOOT".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                String name = decodeText(fields[3]);
                runOnUiThread(() -> { incomingLootName = name; incomingLootItems.clear(); });
            } else if (fields.length == 6 && "STATE".equals(fields[0]) && "LOOTITEM".equals(fields[1])) {
                BarterSummaryView.Item item = new BarterSummaryView.Item("DEPOSIT".equals(fields[2]),
                        decodeText(fields[5]), Integer.parseInt(fields[3]), Double.parseDouble(fields[4]));
                runOnUiThread(() -> incomingLootItems.add(item));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "LOOT".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    StringBuilder signature = new StringBuilder(incomingLootName);
                    for (BarterSummaryView.Item item : incomingLootItems)
                        signature.append('|').append(item.selling).append('|').append(item.count)
                                .append('|').append(item.value).append('|').append(item.name);
                    String next = signature.toString();
                    if (!next.equals(lootSignature)) {
                        lootSignature = next;
                        List<BarterSummaryView.Item> sorted = new ArrayList<>(incomingLootItems);
                        sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                        lootSummary.update(incomingLootName, 0, 0, 0, sorted);
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0]) && "LOOT".equals(fields[1])
                    && ("CLOSED".equals(fields[2]) || "PENDING".equals(fields[2]))) {
                runOnUiThread(() -> { lootSignature = null; lootSummary.clear(); });
            } else if (fields.length == 7 && "STATE".equals(fields[0])
                    && "BARTER".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                String trader = decodeText(fields[3]);
                int player = Integer.parseInt(fields[4]), vendor = Integer.parseInt(fields[5]);
                int net = Integer.parseInt(fields[6]);
                runOnUiThread(() -> {
                    incomingBarterName = trader; incomingPlayerCaps = player;
                    incomingMerchantCaps = vendor; incomingBarterBalance = net;
                    incomingBarterItems.clear();
                });
            } else if (fields.length == 5 && "STATE".equals(fields[0]) && "BARTERITEM".equals(fields[1])) {
                BarterSummaryView.Item item = new BarterSummaryView.Item("SELL".equals(fields[2]),
                        decodeText(fields[4]), Integer.parseInt(fields[3]));
                runOnUiThread(() -> incomingBarterItems.add(item));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "BARTER".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    StringBuilder signature = new StringBuilder(incomingBarterName).append('|')
                            .append(incomingPlayerCaps).append('|').append(incomingMerchantCaps)
                            .append('|').append(incomingBarterBalance);
                    for (BarterSummaryView.Item item : incomingBarterItems)
                        signature.append('|').append(item.selling).append('|').append(item.count).append('|').append(item.name);
                    String next = signature.toString();
                    if (!next.equals(barterSignature)) {
                        barterSignature = next;
                        barterSummary.update(incomingBarterName, incomingPlayerCaps, incomingMerchantCaps,
                                incomingBarterBalance, incomingBarterItems);
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0]) && "BARTER".equals(fields[1])
                    && ("CLOSED".equals(fields[2]) || "PENDING".equals(fields[2]))) {
                runOnUiThread(() -> { barterSignature = null; barterSummary.clear(); });
            } else if ((fields.length == 3 || fields.length == 4)
                    && "STATE".equals(fields[0]) && "ACTIVITY".equals(fields[1])) {
                String activity = fields[2];
                String place = fields.length == 4 ? decodeText(fields[3]) : "";
                runOnUiThread(() -> {
                    gameActivity = activity;
                    gameActivityPlace = place;
                    if (companionHasOpened && "LOADING_SAVE".equals(activity)) reloadInProgress = true;
                    if ("NONE".equals(activity) && gameReadyForNotes) reloadInProgress = false;
                    renderActivityBlock();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0]) && "PLUGINVERSION".equals(fields[1])) {
                String version = fields[2];
                if (version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
                    runOnUiThread(() -> {
                        if (terminalIntro != null) terminalIntro.setPluginVersion(version);
                        showVersionMismatch(version);
                    });
                }
            } else if (fields.length == 5 && "DIALOGUE".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                String speaker = decodeText(fields[3]);
                boolean ready = "1".equals(fields[4]);
                runOnUiThread(() -> {
                    incomingDialogueChoices.clear();
                    dialogueSpeaker = speaker;
                    dialogueReady = ready;
                });
            } else if (fields.length == 3 && "DIALOGUESPEECH".equals(fields[1])) {
                long token = Long.parseUnsignedLong(fields[2]);
                runOnUiThread(() -> dialogueSpeechToken = token);
            } else if (fields.length == 4 && "DIALOGUECHOICE".equals(fields[1])) {
                String token = fields[2], text = decodeText(fields[3]);
                runOnUiThread(() -> incomingDialogueChoices.add(new String[]{token, text}));
            } else if (fields.length == 3 && "DIALOGUE".equals(fields[1])) {
                boolean active = "END".equals(fields[2]);
                runOnUiThread(() -> {
                    boolean wasActive = dialogueActive;
                    dialogueActive = active;
                    dialogueChoices.clear();
                    if (active) dialogueChoices.addAll(incomingDialogueChoices);
                    else {
                        incomingDialogueChoices.clear();
                        dialogueSpeechToken = 0;
                    }
                    renderDialogue();
                    if (active && !wasActive) {
                        sendBridgeCommand("LOCALOFF");
                        if (notesRequested) {
                            notesRequested = false;
                            sendBridgeCommand("NOTESOFF");
                        }
                    } else if (!active && wasActive) {
                        renderPage();
                    }
                });
            } else if(fields.length==9 && "STATE".equals(fields[0]) && "SPECIALMOD".equals(fields[1])) {
                int[] modifiers=new int[7];
                for(int i=0;i<7;i++) modifiers[i]=Integer.parseInt(fields[i+2]);
                runOnUiThread(()->{
                    for(int i=0;i<7;i++) {
                        specialModifiers[i]=modifiers[i];
                        if(specialValues[i]!=null) specialValues[i].setText(specialDisplayValue(i));
                    }
                });
            } else if(fields.length==3 && "ACTIVEEFFECTS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingActiveEffects::clear);
            } else if(fields.length==4 && "ACTIVEEFFECT".equals(fields[1])) {
                String name=decodeText(fields[2]),detail=decodeText(fields[3]);
                runOnUiThread(()->incomingActiveEffects.add(new String[]{name,detail}));
            } else if(fields.length==3 && "ACTIVEEFFECTS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(()->{
                    String signature=java.util.Arrays.deepToString(incomingActiveEffects.toArray());
                    boolean changed=!activeEffectsLoaded || !signature.equals(activeEffectsSignature);
                    activeEffectsLoaded=true;activeEffectsSignature=signature;
                    if(changed) {
                        activeEffectEntries.clear();activeEffectEntries.addAll(incomingActiveEffects);
                        if(selectedSection==0 && selectedSubsection==0) renderPage();
                    }
                });
            } else if(fields.length==6 && "LOCALMARKERS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                long area=Long.parseLong(fields[3]);int cx=Integer.parseInt(fields[4]),cy=Integer.parseInt(fields[5]);
                runOnUiThread(()->{
                    incomingLocalMarkers.clear();incomingLocalMarkerArea=area;
                    incomingLocalMarkerCenterX=cx;incomingLocalMarkerCenterY=cy;
                });
            } else if(fields.length==7 && "LOCALMARKER".equals(fields[1])) {
                MapMarkerEntry marker=new MapMarkerEntry(fields[2].charAt(0),false,
                        Float.parseFloat(fields[3]),Float.parseFloat(fields[4]),
                        Long.parseUnsignedLong(fields[5]),decodeText(fields[6]));
                runOnUiThread(()->incomingLocalMarkers.add(marker));
            } else if(fields.length==3 && "LOCALMARKERS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(()->{
                    localMarkers.clear();localMarkers.addAll(incomingLocalMarkers);
                    localMarkerArea=incomingLocalMarkerArea;
                    localMarkerCenterX=incomingLocalMarkerCenterX;localMarkerCenterY=incomingLocalMarkerCenterY;
                    if(localMapCanvas!=null)localMapCanvas.invalidate();
                });
            } else if ((fields.length == 3 || fields.length == 6) && "LOCALBEGIN".equals(fields[1])) {
                long generation = Long.parseLong(fields[2]);
                boolean layout = fields.length == 6;
                long area = layout ? Long.parseLong(fields[3]) : 0;
                int centerX = layout ? Integer.parseInt(fields[4]) : 0;
                int centerY = layout ? Integer.parseInt(fields[5]) : 0;
                runOnUiThread(() -> {
                    incomingLocalGeneration = generation;
                    incomingLocalArea=area;incomingLocalCenterX=centerX;incomingLocalCenterY=centerY;
                    java.util.Arrays.fill(incomingLocalTiles,null);
                    if(layout && area==localMapArea) {
                        for(int row=0;row<LOCAL_MAP_GRID_SIDE;row++) for(int col=0;col<LOCAL_MAP_GRID_SIDE;col++) {
                            int oldCol=col+centerX-localCenterX;
                            int oldRow=row+localCenterY-centerY;
                            if(oldCol>=0 && oldCol<LOCAL_MAP_GRID_SIDE && oldRow>=0 && oldRow<LOCAL_MAP_GRID_SIDE)
                                incomingLocalTiles[row*LOCAL_MAP_GRID_SIDE+col]=cachedLocalTiles[oldRow*LOCAL_MAP_GRID_SIDE+oldCol];
                        }
                    }
                    localStatus = "RECEIVING LOCAL MAP";
                });
            } else if (fields.length == 3 && "LOCALEND".equals(fields[1])) {
                long generation = Long.parseLong(fields[2]);
                runOnUiThread(() -> {
                    if (generation != incomingLocalGeneration) return;
                    for (Bitmap tile : incomingLocalTiles) if (tile == null) return;
                    int side = LOCAL_MAP_GRID_SIDE * LOCAL_MAP_TILE_SIZE;
                    boolean reuse = localMapBitmap != null && localMapBitmap.isMutable()
                            && localMapBitmap.getWidth() == side && localMapBitmap.getHeight() == side
                            && localMapArea == incomingLocalArea
                            && localCenterX == incomingLocalCenterX && localCenterY == incomingLocalCenterY;
                    Bitmap mosaic = reuse ? localMapBitmap : Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(mosaic);
                    Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
                    for (int i=0;i<LOCAL_MAP_TILE_COUNT;i++) {
                        if (reuse && incomingLocalTiles[i] == cachedLocalTiles[i]) continue;
                        int x=(i%LOCAL_MAP_GRID_SIDE)*LOCAL_MAP_TILE_SIZE, y=(i/LOCAL_MAP_GRID_SIDE)*LOCAL_MAP_TILE_SIZE;
                        canvas.drawBitmap(incomingLocalTiles[i],null,
                                new RectF(x,y,x+LOCAL_MAP_TILE_SIZE,y+LOCAL_MAP_TILE_SIZE),filter);
                    }
                    localMapBitmap=mosaic; localMapGeneration=generation; localStatus="";
                    System.arraycopy(incomingLocalTiles,0,cachedLocalTiles,0,LOCAL_MAP_TILE_COUNT);
                    localMapArea=incomingLocalArea;localCenterX=incomingLocalCenterX;localCenterY=incomingLocalCenterY;
                    if (localMapCanvas!=null) localMapCanvas.invalidate();
                });
            } else if (fields.length == 6 && "LOCALPOS".equals(fields[1])) {
                long generation=Long.parseLong(fields[2]);
                float x=Float.parseFloat(fields[3]),y=Float.parseFloat(fields[4]),heading=Float.parseFloat(fields[5]);
                runOnUiThread(() -> {
                    localPositionGeneration=generation;localPlayerX=x;localPlayerY=y;localHeading=heading;
                    if(localMapCanvas!=null)localMapCanvas.invalidate();
                });
            } else if (fields.length == 3 && "LOCALSTATUS".equals(fields[1])) {
                String status = "OPEN_PIPBOY_LOCAL_MAP_ONCE".equals(fields[2])
                        ? "OPEN IN-GAME LOCAL MAP TO STREAM"
                        : "READY".equals(fields[2]) ? "" : fields[2].replace('_',' ');
                runOnUiThread(() -> {
                    localStatus=status;
                    if(!status.isEmpty()) localMapBitmap=null;
                    if(localMapCanvas!=null)localMapCanvas.invalidate();
                });
            } else if (fields.length == 7 && "STATE".equals(fields[0])
                    && "MAPDATA".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                String key = fields[3];
                int payloadLength = Integer.parseInt(fields[4]);
                int decodedLength = Integer.parseInt(fields[5]);
                boolean compressed = "1".equals(fields[6]);
                if (payloadLength < 1 || payloadLength > 16 * 1024 * 1024
                        || decodedLength < 1 || decodedLength > 16 * 1024 * 1024
                        || !("MOJAVE".equals(key) || "DEAD_MONEY".equals(key)
                        || "HONEST_HEARTS".equals(key) || "OLD_WORLD_BLUES".equals(key)
                        || "LONESOME_ROAD".equals(key) || localTileIndex(key) >= 0)) {
                    throw new IllegalArgumentException("Invalid map transfer header");
                }
                incomingMapKey = key;
                incomingMapPayloadLength = payloadLength;
                incomingMapDecodedLength = decodedLength;
                incomingMapCompressed = compressed;
                incomingMapPayload = new ByteArrayOutputStream(payloadLength);
                runOnUiThread(() -> {
                    if (key.equals(currentWorldKey)) {
                        mapLoadStatus = "RECEIVING MAP FROM GAME";
                        if (mapCanvas != null) mapCanvas.invalidate();
                    }
                });
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "MAPDATA".equals(fields[1]) && "CHUNK".equals(fields[2])) {
                if (incomingMapPayload != null && fields[3].equals(incomingMapKey)) {
                    byte[] chunk = Base64.decode(fields[4], Base64.DEFAULT);
                    if (incomingMapPayload.size() + chunk.length > incomingMapPayloadLength) {
                        throw new IllegalArgumentException("Map transfer exceeded declared size");
                    }
                    incomingMapPayload.write(chunk, 0, chunk.length);
                }
            } else if (fields.length == 4 && "STATE".equals(fields[0])
                    && "MAPDATA".equals(fields[1]) && "END".equals(fields[2])) {
                finishMapTransfer(fields[3]);
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "MAPDATA".equals(fields[1]) && "ERROR".equals(fields[2])) {
                String key = fields[3];
                if (key.equals(requestedMapKey)) mapRequestPending = false;
                runOnUiThread(() -> {
                    if (key.equals(currentWorldKey)) {
                        mapLoadStatus = "GAME MAP TEXTURE NOT FOUND";
                        if (mapCanvas != null) mapCanvas.invalidate();
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "MAPSPACE".equals(fields[1])) {
                long space = Long.parseUnsignedLong(fields[2]);
                runOnUiThread(() -> currentMapSpaceId = space);
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "WORLD".equals(fields[1])) {
                String worldName = decodeText(fields[2]);
                runOnUiThread(() -> {
                    String previousWorldKey = currentWorldKey;
                    selectWorldMap(worldName);
                    if (!previousWorldKey.equals(currentWorldKey)) {
                        viewedQuestDestination = null;
                        pendingQuestViewRequest = 0;
                        mapPlayerX = mapPlayerY = Float.NaN;
                        customMapX = customMapY = Float.NaN;
                        questDestinations.clear();
                        mapMarkers.clear();
                    }
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "GAME".equals(fields[1]) && "READY".equals(fields[2])) {
                runOnUiThread(() -> {
                    boolean becameReady = !gameReadyForNotes;
                    gameReadyForNotes = true;
                    if (!"LOADING_SAVE".equals(gameActivity)) reloadInProgress = false;
                    renderActivityBlock();
                    if (becameReady && !dialogueActive && !interactionBlocked
                            && selectedSection == 2 && selectedSubsection == 2
                            && !notesRequested) {
                        notesRequested = true;
                        sendBridgeCommand("NOTESON");
                    }
                    showPipBoy();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "GAME".equals(fields[1]) && "WAITING".equals(fields[2])) {
                runOnUiThread(() -> {
                    gameReadyForNotes = false;
                    challengeEntries.clear();
                    incomingChallenges.clear();
                    challengeDescriptions.clear();
                    perkDescriptions.clear();
                    perkEntries.clear();
                    incomingPerkEntries.clear();
                    perksLoaded = false;
                    perkSignature = null;
                    expandedPerk = pendingPerkDetail = 0;
                    challengesLoaded = false;
                    challengeSignature = null;
                    expandedChallenge = pendingChallengeDetail = 0;
                    if (mapSearchDialog != null) mapSearchDialog.dismiss();
                    pendingQuestViewRequest = 0;
                    viewedQuestDestination = null;
                    cachedInventoryWeight = "--/--";
                    if (inventoryWeight != null) inventoryWeight.setText(cachedInventoryWeight);
                    dropItemPending = false;
                    if (expandedInventoryForm != 0) closeInventoryDetails();
                    hotkeyNames.clear();
                    incomingHotkeyNames.clear();
                    weaponMods.clear();
                    incomingWeaponMods.clear();
                    weaponModsForm = weaponModsToken = 0;
                    incomingModsForm = incomingModsToken = 0;
                    if ((activeModList != null || inventoryOverviewList != null || healingChoices != null)
                            && contentDialog != null) contentDialog.dismiss();
                    updateHotkeyPicker();
                    if (notesRequested) {
                        notesRequested = false;
                        sendBridgeCommand("NOTESOFF");
                    }
                    if (companionHasOpened && (reloadInProgress || !"NONE".equals(gameActivity))) {
                        renderActivityBlock();
                    } else {
                        showWaitingIntro(TerminalIntroView.WAITING_FOR_SAVE);
                    }
                    questDestinations.clear();
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length >= 3 && "STATE".equals(fields[0])
                    && "ENCUMBRANCE".equals(fields[1])) {
                String weight = "--/--";
                if (fields.length == 4) {
                    double carried = Double.parseDouble(fields[2]);
                    double capacity = Double.parseDouble(fields[3]);
                    if (Double.isFinite(carried) && Double.isFinite(capacity)) {
                        weight = String.format(Locale.ROOT, "%.0f/%.0f",
                                Math.floor(Math.max(0, carried)), Math.floor(Math.max(0, capacity)));
                    }
                }
                final String displayWeight = weight;
                runOnUiThread(() -> {
                    cachedInventoryWeight = displayWeight;
                    if (inventoryWeight != null) {
                        inventoryWeight.setText(displayWeight);
                        inventoryWeight.setContentDescription("Carried weight / maximum capacity: " + displayWeight);
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "MAPPOINT".equals(fields[1]) && "UNAVAILABLE".equals(fields[2])) {
                runOnUiThread(() -> {
                    mapPlayerX = Float.NaN;
                    mapPlayerY = Float.NaN;
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length == 4 && "STATE".equals(fields[0])
                    && "MAPPOINT".equals(fields[1])) {
                float x = Float.parseFloat(fields[2]);
                float y = Float.parseFloat(fields[3]);
                runOnUiThread(() -> {
                    mapPlayerX = x;
                    mapPlayerY = y;
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "CUSTOMMARKEREXISTS".equals(fields[1])) {
                boolean exists = "1".equals(fields[2]);
                runOnUiThread(() -> customMarkerExists = exists);
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "CUSTOMMARKER".equals(fields[1])
                    && "UNAVAILABLE".equals(fields[2])) {
                runOnUiThread(() -> {
                    customMapX = customMapY = Float.NaN;
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length == 4 && "STATE".equals(fields[0])
                    && "CUSTOMMARKER".equals(fields[1])) {
                float x = Float.parseFloat(fields[2]);
                float y = Float.parseFloat(fields[3]);
                runOnUiThread(() -> {
                    customMapX = x;
                    customMapY = y;
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if ((fields.length == 5 || fields.length == 7) && "STATE".equals(fields[0])
                    && "QUESTVIEW".equals(fields[1])) {
                long request = Long.parseUnsignedLong(fields[2]);
                long quest = Long.parseUnsignedLong(fields[3]);
                boolean available = fields.length == 7 && "OK".equals(fields[4]);
                float x = available ? Float.parseFloat(fields[5]) : Float.NaN;
                float y = available ? Float.parseFloat(fields[6]) : Float.NaN;
                runOnUiThread(() -> {
                    if (request != pendingQuestViewRequest || quest != pendingQuestViewForm) return;
                    pendingQuestViewRequest = 0;
                    if (interactionBlocked || dialogueActive || vatsActive
                            || selectedSection != 2 || selectedSubsection != 1) return;
                    if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
                        showQuestMarkerPrompt(null, "This quest has no available marker on the current world map.", false);
                        return;
                    }
                    navigateWithShutter(() -> {
                        if (interactionBlocked || dialogueActive || vatsActive) return;
                        selectedSection = 2;
                        selectedSubsection = 0;
                        localMapSelected = false;
                        viewedQuestDestination = new QuestDestination(x, y);
                        viewedQuestForm = quest;
                        buildSubTabs();
                        renderPage();
                    });
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "QUESTDESTINATIONS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingQuestDestinations::clear);
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "QUESTDESTINATION".equals(fields[1])) {
                long refId = Long.parseUnsignedLong(fields[2]);
                float x = Float.parseFloat(fields[3]);
                float y = Float.parseFloat(fields[4]);
                runOnUiThread(() -> incomingQuestDestinations.put(refId,
                        new QuestDestination(x, y)));
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "QUESTPATH".equals(fields[1])) {
                long refId = Long.parseUnsignedLong(fields[2]);
                float x = Float.parseFloat(fields[3]);
                float y = Float.parseFloat(fields[4]);
                runOnUiThread(() -> {
                    QuestDestination destination = incomingQuestDestinations.get(refId);
                    if (destination != null) destination.route.add(new PointF(x, y));
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "QUESTDESTINATIONS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    // Publish all destinations and their routes together after the batch finishes.
                    questDestinations.clear();
                    questDestinations.addAll(incomingQuestDestinations.values());
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "HEADING".equals(fields[1])) {
                float heading = Float.parseFloat(fields[2]);
                runOnUiThread(() -> {
                    mapPlayerHeading = heading;
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "RADIO".equals(fields[1])) {
                boolean playing = !"NONE".equals(fields[2]);
                String station = "NONE".equals(fields[2])
                        ? "NO STATION PLAYING" : decodeText(fields[2]);
                runOnUiThread(() -> {
                    boolean changed = !cachedRadioStation.equals(station);
                    cachedRadioStation = station;
                    cachedRadioPlaying = playing;
                    if (changed && radioCurrentValue != null) radioCurrentValue.setText(station);
                    if (radioWaveform != null) {
                        int visibility = playing ? View.VISIBLE : View.INVISIBLE;
                        if (radioWaveform.getVisibility() != visibility) radioWaveform.setVisibility(visibility);
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "RADIOSTATIONS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingRadioStations::clear);
            } else if ((fields.length == 3 || fields.length == 4) && "STATE".equals(fields[0])
                    && "RADIOSTATION".equals(fields[1])) {
                RadioStation station = fields.length == 4
                        ? new RadioStation(Long.parseUnsignedLong(fields[2]), decodeText(fields[3]))
                        : new RadioStation(0, decodeText(fields[2]));
                runOnUiThread(() -> {
                    boolean duplicate = false;
                    for (RadioStation existing : incomingRadioStations) {
                        if (existing.name.equals(station.name)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        incomingRadioStations.add(station);
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "RADIOSTATIONS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    boolean changed = radioStations.size() != incomingRadioStations.size();
                    if (!changed) {
                        for (int i = 0; i < radioStations.size(); i++) {
                            RadioStation old = radioStations.get(i), next = incomingRadioStations.get(i);
                            if (old.refId != next.refId || !old.name.equals(next.name)) {
                                changed = true;
                                break;
                            }
                        }
                    }
                    if (changed) {
                        radioStations.clear();
                        radioStations.addAll(incomingRadioStations);
                    }
                    if (selectedSection == 2 && selectedSubsection == 3
                            && (changed || !renderedRadioStation.equals(cachedRadioStation))) renderPage();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "MARKERS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingMapMarkers::clear);
            } else if ((fields.length == 7 || fields.length == 8) && "STATE".equals(fields[0])
                    && "MARKER".equals(fields[1])) {
                MapMarkerEntry marker = new MapMarkerEntry(
                        Integer.parseInt(fields[2]), "1".equals(fields[3]),
                        Float.parseFloat(fields[4]), Float.parseFloat(fields[5]),
                        fields.length == 8 ? Long.parseUnsignedLong(fields[6]) : 0,
                        decodeText(fields[fields.length - 1]));
                runOnUiThread(() -> incomingMapMarkers.add(marker));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "MARKERS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    mapMarkers.clear();
                    mapMarkers.addAll(incomingMapMarkers);
                    updateMapSearchLocations();
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length == 5 && "STATE".equals(fields[0]) && "LIMBCARE".equals(fields[1])) {
                int count = Math.max(0, Integer.parseInt(fields[2]));
                boolean hardcore = "1".equals(fields[3]);
                int mask = Integer.parseInt(fields[4]) & 63;
                runOnUiThread(() -> {
                    doctorsBagCount = count;
                    crippledLimbMask = mask;
                    boolean modeChanged = cachedHardcore != hardcore;
                    cachedHardcore = hardcore;
                    if (modeChanged && selectedSection == 0 && selectedSubsection == 0) renderPage();
                    if (limbConditionView != null) limbConditionView.invalidate();
                });
            } else if (fields.length == 4 && "STATE".equals(fields[0]) && "LIMBRESULT".equals(fields[1])) {
                long sequence = Long.parseUnsignedLong(fields[2]);
                String result = fields[3];
                runOnUiThread(() -> {
                    if (lastLimbResultSequence == sequence) return;
                    lastLimbResultSequence = sequence;
                    if (!limbTreatmentPending) return;
                    limbTreatmentPending = false;
                    String message = switch (result) {
                        case "NO_BAG" -> "No Doctor’s Bag available";
                        case "NO_CRIPPLED" -> "No limbs are currently crippled.";
                        case "LIMB_CHANGED" -> "That limb is no longer crippled. Select a crippled limb again.";
                        case "OK" -> "";
                        default -> "Treatment could not be confirmed. Check your limb condition and inventory in-game.";
                    };
                    if (!message.isEmpty() && !interactionBlocked && !dialogueActive && !vatsActive)
                        showLimbCarePopup("DOCTOR’S BAG", message, 0, -1);
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "TEXTENTRY".equals(fields[1]) && "CLOSED".equals(fields[2])) {
                runOnUiThread(() -> {
                    textEntrySession = 0;
                    if (gameKeyboard != null) gameKeyboard.setVisibility(View.GONE);
                });
            } else if (fields.length == 6 && "STATE".equals(fields[0]) && "TEXTENTRY".equals(fields[1])) {
                long session = Long.parseUnsignedLong(fields[2]);
                boolean accept = "1".equals(fields[3]);
                String prompt = decodeText(fields[4]), text = decodeText(fields[5]);
                runOnUiThread(() -> {
                    if (session == 0 || gameKeyboard == null) return;
                    if (textEntrySession != session) {
                        if (contentDialog != null) contentDialog.dismiss();
                        if (contentPopup != null) contentPopup.dismiss();
                        if (mapSearchDialog != null) mapSearchDialog.dismiss();
                        dismissVersionMismatch();
                        gameKeyboard.beginSession();
                    }
                    textEntrySession = session;
                    gameKeyboard.update(prompt, text, accept);
                    gameKeyboard.setVisibility(View.VISIBLE);
                    gameKeyboard.bringToFront();
                });
            } else if (fields.length == 6 && "STATE".equals(fields[0]) && "EXPERIENCE".equals(fields[1])) {
                int level = Integer.parseInt(fields[2]);
                float current = Float.parseFloat(fields[3]), required = Float.parseFloat(fields[4]);
                boolean valid = level > 0 && Float.isFinite(current) && Float.isFinite(required)
                        && current >= 0 && required > 0;
                String progress = valid ? fields[3] : "0", maximum = valid ? fields[4] : "0";
                boolean capped = "1".equals(fields[5]);
                runOnUiThread(() -> {
                    cachedPlayerLevel = valid ? level : 0;
                    cachedLevelExperience = progress;
                    cachedLevelExperienceRequired = maximum;
                    cachedLevelCapped = valid && capped;
                    updateExperienceDisplay();
                });
            } else if (fields.length == 6 && "STATE".equals(fields[0]) && "STATUS".equals(fields[1])) {
                String maxHealth = Integer.parseInt(fields[2]) < 0 ? "--.-" : formatTenths(Integer.parseInt(fields[2]));
                String maxAp = Integer.parseInt(fields[3]) < 0 ? "--.-" : formatTenths(Integer.parseInt(fields[3]));
                boolean hardcore = "1".equals(fields[4]);
                String date = "-".equals(fields[5]) ? "--.--.---- --:--" : decodeText(fields[5]);
                runOnUiThread(() -> {
                    boolean modeChanged = cachedHardcore != hardcore;
                    cachedMaxHealth = maxHealth; cachedMaxActionPoints = maxAp;
                    cachedHardcore = hardcore; cachedGameDate = date;
                    if (modeChanged && selectedSection == 0 && selectedSubsection == 0) renderPage();
                    updateVitalDisplays();
                    updateStatusClock();
                });
            } else if (fields.length == 4 && "STATE".equals(fields[0])
                    && "VITALS".equals(fields[1])) {
                int healthTenths = Integer.parseInt(fields[2]);
                int actionPointsTenths = Integer.parseInt(fields[3]);
                String health = formatTenths(healthTenths);
                String actionPoints = formatTenths(actionPointsTenths);
                runOnUiThread(() -> {
                    cachedHealth = health;
                    cachedActionPoints = actionPoints;
                    updateVitalDisplays();
                });
            } else if (fields.length == 9 && "STATE".equals(fields[0])
                    && "SPECIAL".equals(fields[1])) {
                String[] values = new String[7];
                for (int i = 0; i < values.length; i++) {
                    int value = Integer.parseInt(fields[i + 2]);
                    values[i] = formatTenths(Math.min(value, 100));
                }
                runOnUiThread(() -> {
                    for (int i = 0; i < values.length; i++) {
                        cachedSpecialValues[i] = values[i];
                        if (specialValues[i] != null) specialValues[i].setText(specialDisplayValue(i));
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "HEALTH".equals(fields[1])) {
                runOnUiThread(() -> {
                    cachedHealth = fields[2];
                    updateVitalDisplays();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "HOTKEYS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingHotkeyNames::clear);
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "HOTKEY".equals(fields[1])) {
                int slot = Integer.parseInt(fields[2]);
                String name = decodeText(fields[4]);
                if (slot >= 1 && slot <= 8 && slot != 2)
                    runOnUiThread(() -> incomingHotkeyNames.put(slot, name));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "HOTKEYS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    hotkeyNames.clear();
                    hotkeyNames.putAll(incomingHotkeyNames);
                    updateHotkeyPicker();
                });
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "WEAPONMODS".equals(fields[1])) {
                long formId = Long.parseUnsignedLong(fields[3]);
                long token = Long.parseUnsignedLong(fields[4]);
                if ("BEGIN".equals(fields[2])) runOnUiThread(() -> {
                    incomingWeaponMods.clear();
                    incomingModsForm = formId;
                    incomingModsToken = token;
                });
                else if ("END".equals(fields[2])) runOnUiThread(() -> {
                    if (incomingModsForm != formId || incomingModsToken != token) return;
                    boolean changed = weaponModsForm != formId || weaponModsToken != token
                            || !weaponMods.equals(incomingWeaponMods);
                    weaponModsForm = formId;
                    weaponModsToken = token;
                    weaponMods.clear();
                    weaponMods.addAll(incomingWeaponMods);
                    if (changed) updateWeaponModMenu();
                });
            } else if (fields.length == 8 && "STATE".equals(fields[0])
                    && "WEAPONMOD".equals(fields[1])) {
                long formId = Long.parseUnsignedLong(fields[2]);
                long token = Long.parseUnsignedLong(fields[3]);
                WeaponModEntry mod = new WeaponModEntry(Long.parseUnsignedLong(fields[4]),
                        Integer.parseInt(fields[5]), "1".equals(fields[6]), decodeText(fields[7]));
                runOnUiThread(() -> {
                    if (incomingModsForm == formId && incomingModsToken == token) incomingWeaponMods.add(mod);
                });
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "MODRESULT".equals(fields[1])) {
                long sequence = Long.parseUnsignedLong(fields[2]);
                boolean success = "1".equals(fields[3]);
                String message = decodeText(fields[4]);
                runOnUiThread(() -> {
                    if (lastModResultSequence == sequence) return;
                    lastModResultSequence = sequence;
                    if (!modInstallPending) return;
                    modInstallPending = false;
                    if (contentDialog != null) contentDialog.dismiss();
                    if (!success) Log.w("FNVDS", message);
                });
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "DROPRESULT".equals(fields[1])) {
                long sequence = Long.parseUnsignedLong(fields[2]);
                boolean success = "1".equals(fields[3]);
                String message = decodeText(fields[4]);
                runOnUiThread(() -> {
                    if (lastDropResultSequence == sequence) return;
                    lastDropResultSequence = sequence;
                    if (!dropItemPending) return;
                    dropItemPending = false;
                    if (!success) Log.w("FNVDS", message);
                });
            } else if ((fields.length == 6 || fields.length == 7) && "STATE".equals(fields[0])
                    && "ITEMDETAILS".equals(fields[1])) {
                long formId = Long.parseUnsignedLong(fields[2]);
                long token = Long.parseUnsignedLong(fields[3]);
                boolean canMod = "1".equals(fields[4]);
                boolean canDrop = fields.length == 7 && "1".equals(fields[5]);
                String detail = decodeText(fields[fields.length - 1]);
                runOnUiThread(() -> {
                    if (expandedInventoryForm != formId || expandedInventoryToken != token) return;
                    cachedInventoryDetails = detail;
                    cachedInventoryCanMod = canMod;
                    cachedInventoryCanDrop = canDrop;
                    if (expandedInventoryView != null
                            && expandedInventoryView.setDetails(detail, canMod, canDrop)) {
                        animateInventoryDetails(expandedInventoryView, true);
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "INVENTORY".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                // Keep the displayed inventory until END replaces it with the completed incoming list.
                runOnUiThread(incomingInventoryItems::clear);
            } else if (((fields.length >= 7 && fields.length <= 10) || fields.length == 14 || fields.length == 18 || fields.length == 20 || fields.length == 21)
                    && "STATE".equals(fields[0])
                    && "ITEM".equals(fields[1])) {
                InventoryEntry item = new InventoryEntry(fields[2],
                        Integer.parseInt(fields[3]), Long.parseUnsignedLong(fields[4]),
                        "1".equals(fields[5]), fields.length >= 8 && "1".equals(fields[6]),
                        fields.length < 9 || "1".equals(fields[7]),
                        fields.length >= 10 ? fields[8] : "OTHER",
                        fields.length >= 14 ? Long.parseUnsignedLong(fields[9]) : 0,
                        fields.length >= 14 ? Integer.parseInt(fields[10]) : -1,
                        fields.length >= 14 ? Integer.parseInt(fields[11]) : 0,
                        fields.length >= 14 ? Integer.parseInt(fields[12]) : 0,
                        decodeText(fields[fields.length - 1]));
                if (fields.length >= 18) {
                    // Normalize before inventory sorting and stack totals use this value.
                    item.weight = Math.max(0.0, Double.parseDouble(fields[13]));
                    item.value = Double.parseDouble(fields[14]);
                    item.canDrop = "1".equals(fields[15]);
                    item.medicine = Integer.parseInt(fields[16]);
                    if (fields.length >= 20) {
                        item.foodRelief = "-".equals(fields[17]) ? "" : decodeText(fields[17]);
                        item.waterRelief = "-".equals(fields[18]) ? "" : decodeText(fields[18]);
                        if (fields.length == 21) item.sleepRelief = "-".equals(fields[19]) ? "" : decodeText(fields[19]);
                    }
                }
                runOnUiThread(() -> incomingInventoryItems.add(item));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "INVENTORY".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    boolean changed = !inventoryLoaded || !sameInventory(inventoryItems, incomingInventoryItems);
                    followExpandedEquip(incomingInventoryItems);
                    inventoryItems.clear();
                    inventoryItems.addAll(incomingInventoryItems);
                    inventoryLoaded = true;
                    if (selectedSection == 1 && changed) renderPage();
                    if (changed && inventoryOverviewList != null) refreshInventoryOverview();
                    if (changed && healingChoices != null) refreshHealingChoices();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "QUESTS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingQuestEntries::clear);
            } else if ((fields.length == 4 || fields.length >= 6)
                    && "STATE".equals(fields[0])
                    && "QUEST".equals(fields[1])) {
                QuestEntry quest;
                if (fields.length >= 6) {
                    String encodedOptionals = fields.length > 6
                            ? String.join(" ", java.util.Arrays.copyOfRange(fields, 6, fields.length))
                            : "";
                    String decodedOptionals = encodedOptionals.isEmpty()
                            ? "" : decodeText(encodedOptionals);
                    String[] optionalObjectives = decodedOptionals.isEmpty()
                            ? new String[0] : decodedOptionals.split("\\n");
                    quest = new QuestEntry(Long.parseUnsignedLong(fields[2]), "1".equals(fields[3]),
                            decodeText(fields[4]), decodeText(fields[5]), optionalObjectives);
                } else {
                    quest = new QuestEntry(0, false, decodeText(fields[2]), decodeText(fields[3]),
                            new String[0]);
                }
                runOnUiThread(() -> incomingQuestEntries.add(quest));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "QUESTS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    questEntries.clear();
                    questEntries.addAll(incomingQuestEntries);
                    questsLoaded = true;
                    if (selectedSection == 2 && selectedSubsection == 1) renderPage();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "NOTES".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(() -> {
                    incomingNoteEntries.clear();
                    if (!notesLoaded) noteEntries.clear();
                });
            } else if (fields.length == 7 && "STATE".equals(fields[0])
                    && "NOTE".equals(fields[1])) {
                NoteEntry note = new NoteEntry(Long.parseUnsignedLong(fields[2]),
                        "1".equals(fields[3]), Integer.parseInt(fields[4]),
                        decodeText(fields[5]), decodeText(fields[6]));
                runOnUiThread(() -> {
                    incomingNoteEntries.add(note);
                    if (!notesLoaded || noteEntries.isEmpty()) {
                        // Reveal the first note immediately instead of keeping the loader until NOTES END.
                        noteEntries.add(note);
                        if (noteEntries.size() == 1 && selectedSection == 2
                                && selectedSubsection == 2) renderPage();
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "NOTES".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    noteEntries.clear();
                    noteEntries.addAll(incomingNoteEntries);
                    notesLoaded = true;
                    if (selectedSection == 2 && selectedSubsection == 2) renderPage();
                });
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "NOTESPROGRESS".equals(fields[1])) {
                boolean active = "1".equals(fields[4]);
                runOnUiThread(() -> {
                    // Counts are not displayed; only scan-state changes affect the empty-list loader.
                    boolean changed = noteScanActive != active;
                    noteScanActive = active;
                    if (changed && (!notesLoaded || noteEntries.isEmpty())
                            && selectedSection == 2 && selectedSubsection == 2) {
                        renderPage();
                    }
                });
            } else if (fields.length >= 2 && "STATE".equals(fields[0])
                    && "NOTEAUDIO".equals(fields[1])) {
                // Accept optional elapsed time and variable whitespace in playback packets.
                String[] audioFields = line.trim().split("\\s+");
                if (audioFields.length < 4) return;
                long formId = Long.parseUnsignedLong(audioFields[2]);
                long nextPlayingId = "1".equals(audioFields[3]) ? formId : 0;
                long durationMs = audioFields.length >= 5
                        ? Long.parseLong(audioFields[4]) : 0;
                long elapsedMs = audioFields.length >= 6
                        ? Long.parseLong(audioFields[5]) : 0;
                runOnUiThread(() -> {
                    boolean changed = playingNoteFormId != nextPlayingId
                            || playingNoteDurationMs != durationMs;
                    playingNoteFormId = nextPlayingId;
                    playingNoteDurationMs = durationMs;
                    playingNoteElapsedMs = elapsedMs;
                    if (activeNotePlaybackRuler != null) {
                        activeNotePlaybackRuler.updatePlayback(durationMs, elapsedMs);
                    }
                    if (changed) {
                        if (selectedSection == 2 && selectedSubsection == 2) renderPage();
                    }
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "NOTEAUDIOERROR".equals(fields[1]) && "1".equals(fields[2])) {
                Log.w("FNVDS", "Unable to play this audio note.");
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "CHALLENGES".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingChallenges::clear);
            } else if (fields.length == 8 && "STATE".equals(fields[0])
                    && "CHALLENGE".equals(fields[1])) {
                ChallengeEntry entry = new ChallengeEntry(Long.parseUnsignedLong(fields[2]),
                        decodeText(fields[7]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
                        "1".equals(fields[5]), "1".equals(fields[6]));
                runOnUiThread(() -> incomingChallenges.add(entry));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "CHALLENGES".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    StringBuilder signature = new StringBuilder();
                    boolean expandedExists = false;
                    for (ChallengeEntry entry : incomingChallenges) {
                        signature.append(entry.formId).append('|').append(entry.name).append('|')
                                .append(entry.progress).append('|').append(entry.threshold).append('|')
                                .append(entry.completed).append('|').append(entry.recurring).append(';');
                        if (entry.formId == expandedChallenge) expandedExists = true;
                    }
                    if (!expandedExists) expandedChallenge = 0;
                    String next = signature.toString();
                    boolean changed = !challengesLoaded || !next.equals(challengeSignature);
                    challengeSignature = next;
                    challengeEntries.clear();
                    challengeEntries.addAll(incomingChallenges);
                    challengesLoaded = true;
                    if (changed && selectedSection == 2 && selectedSubsection == 5) renderPage();
                });
            } else if (fields.length == 4 && "STATE".equals(fields[0])
                    && "CHALLENGEDETAIL".equals(fields[1])) {
                long form = Long.parseUnsignedLong(fields[2]);
                String body = "-".equals(fields[3]) ? "" : decodeText(fields[3]);
                runOnUiThread(() -> {
                    boolean changed = !body.equals(challengeDescriptions.put(form, body));
                    if (pendingChallengeDetail == form) pendingChallengeDetail = 0;
                    if (changed && expandedChallenge == form && selectedSection == 2
                            && selectedSubsection == 5) renderPage();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "GENERALSTATS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingGeneralStats::clear);
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "GENERALSTAT".equals(fields[1])) {
                String name = decodeText(fields[4]);
                String value = Integer.toString(Integer.parseInt(fields[3]));
                runOnUiThread(() -> incomingGeneralStats.add(new String[]{name, value}));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "GENERALSTATS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    StringBuilder signature = new StringBuilder();
                    for (String[] stat : incomingGeneralStats)
                        signature.append(stat[0]).append('|').append(stat[1]).append(';');
                    String next = signature.toString();
                    boolean changed = !generalStatsLoaded || !next.equals(generalStatsSignature);
                    generalStatsSignature = next;
                    generalStats.clear();
                    generalStats.addAll(incomingGeneralStats);
                    generalStatsLoaded = true;
                    if (changed && selectedSection == 2 && selectedSubsection == 4) renderPage();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "PERKS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingPerkEntries::clear);
            } else if ((fields.length == 4 || fields.length == 5) && "STATE".equals(fields[0])
                    && "PERK".equals(fields[1])) {
                PerkEntry perk = fields.length == 5
                        ? new PerkEntry(Long.parseUnsignedLong(fields[2]), decodeText(fields[4]), Integer.parseInt(fields[3]))
                        : new PerkEntry(0, decodeText(fields[3]), Integer.parseInt(fields[2]));
                runOnUiThread(() -> incomingPerkEntries.add(perk));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "PERKS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    incomingPerkEntries.sort((left, right) -> left.name.compareToIgnoreCase(right.name));
                    StringBuilder signature = new StringBuilder();
                    boolean expandedPresent = false;
                    for (PerkEntry entry : incomingPerkEntries) {
                        signature.append(entry.formId).append('|').append(entry.rank).append('|').append(entry.name).append(';');
                        if (entry.formId == expandedPerk) expandedPresent = true;
                    }
                    String next = signature.toString();
                    boolean changed = !perksLoaded || !next.equals(perkSignature);
                    perkSignature = next;
                    if (!expandedPresent) expandedPerk = 0;
                    perkEntries.clear();
                    perkEntries.addAll(incomingPerkEntries);
                    perksLoaded = true;
                    if (changed) refreshPerksPage();
                });
            } else if (fields.length == 4 && "STATE".equals(fields[0]) && "PERKDETAIL".equals(fields[1])) {
                long id = Long.parseUnsignedLong(fields[2]);
                String body = "-".equals(fields[3]) ? "" : decodeText(fields[3]);
                runOnUiThread(() -> {
                    boolean owned = false;
                    for (PerkEntry entry : perkEntries) if (entry.formId == id) { owned = true; break; }
                    if (!owned) return;
                    String previous = perkDescriptions.put(id, body);
                    if (pendingPerkDetail == id) pendingPerkDetail = 0;
                    if (!body.equals(previous) && expandedPerk == id) refreshPerksPage();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "REPUTATIONS".equals(fields[1]) && "BEGIN".equals(fields[2])) {
                runOnUiThread(incomingReputationEntries::clear);
            } else if (fields.length == 4 && "STATE".equals(fields[0])
                    && "REPUTATION".equals(fields[1])) {
                ReputationEntry entry = new ReputationEntry(decodeText(fields[2]),
                        Integer.parseInt(fields[3]));
                runOnUiThread(() -> incomingReputationEntries.add(entry));
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "REPUTATIONS".equals(fields[1]) && "END".equals(fields[2])) {
                runOnUiThread(() -> {
                    incomingReputationEntries.sort((left, right) -> left.name.compareToIgnoreCase(right.name));
                    StringBuilder signature = new StringBuilder();
                    for (ReputationEntry entry : incomingReputationEntries) {
                        signature.append(entry.name).append('|').append(entry.titleCode).append(';');
                    }
                    String nextSignature = signature.toString();
                    boolean changed = !reputationsLoaded
                            || !nextSignature.equals(reputationSignature);
                    reputationSignature = nextSignature;
                    reputationEntries.clear();
                    reputationEntries.addAll(incomingReputationEntries);
                    reputationsLoaded = true;
                    if (changed && selectedSection == 0 && selectedSubsection == 4) renderPage();
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "KARMA".equals(fields[1])) {
                int level = Integer.parseInt(fields[2]);
                String title = karmaTitleName(level);
                runOnUiThread(() -> {
                    if (level != cachedKarmaLevel || !title.equals(cachedKarmaTitle)) {
                        cachedKarmaLevel = level;
                        cachedKarmaTitle = title;
                        if (selectedSection == 0 && selectedSubsection == 4) renderPage();
                    }
                });
            } else if (fields.length == 16 && "STATE".equals(fields[0])
                    && "SKILLS".equals(fields[1])) {
                String[] values = new String[14];
                for (int i = 0; i < values.length; i++) {
                    values[i] = formatTenths(Integer.parseInt(fields[i + 2]));
                }
                runOnUiThread(() -> {
                    System.arraycopy(values, 0, cachedSkillValues, 0, values.length);
                    if (selectedSection == 0 && selectedSubsection == 2) renderPage();
                });
            } else if (fields.length == 6 && "STATE".equals(fields[0])
                    && "EFFECTS".equals(fields[1])) {
                String[] values = new String[4];
                for (int i = 0; i < values.length; i++) {
                    values[i] = formatTenths(Integer.parseInt(fields[i + 2]));
                }
                runOnUiThread(() -> {
                    System.arraycopy(values, 0, cachedEffectValues, 0, values.length);
                    dismissUnneededHealingPopup();
                    if (selectedSection == 0 && selectedSubsection == 0) {
                        renderPage();
                    }
                });
            } else if (fields.length == 8 && "STATE".equals(fields[0])
                    && "LIMBS".equals(fields[1])) {
                String[] values = new String[6];
                for (int i = 0; i < values.length; i++) {
                    values[i] = formatLimbCondition(Integer.parseInt(fields[i + 2]));
                }
                runOnUiThread(() -> {
                    System.arraycopy(values, 0, cachedLimbValues, 0, values.length);
                    if (limbConditionView != null) limbConditionView.setValues(values);
                });
            } else if (fields.length == 3 && "STATE".equals(fields[0])
                    && "MAP".equals(fields[1]) && "UNAVAILABLE".equals(fields[2])) {
                runOnUiThread(() -> {
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            } else if (fields.length == 5 && "STATE".equals(fields[0])
                    && "MAP".equals(fields[1])) {
                runOnUiThread(() -> {
                    if (mapCanvas != null) mapCanvas.invalidate();
                });
            }
        } catch (NumberFormatException ignored) {
            // Ignore malformed or unsupported messages and keep the last valid display.
            if (line.startsWith("STATE QUEST ")) {
                Log.w("FNVDS", "Ignored malformed quest packet: " + line, ignored);
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed text records and keep the last valid display.
            if (line.startsWith("STATE QUEST ")) {
                Log.w("FNVDS", "Ignored malformed quest packet: " + line, ignored);
            }
        }
    }

    private static int localTileIndex(String key) {
        if (key == null || !key.startsWith("LOCAL")) return -1;
        try {
            int index = Integer.parseInt(key.substring(5));
            return index >= 0 && index < LOCAL_MAP_TILE_COUNT ? index : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void finishMapTransfer(String key) {
        ByteArrayOutputStream transfer = incomingMapPayload;
        String transferKey = incomingMapKey;
        int expectedPayloadLength = incomingMapPayloadLength;
        int expectedDecodedLength = incomingMapDecodedLength;
        boolean compressed = incomingMapCompressed;
        // Detach the completed transfer from the receiver before validating or decoding its bytes.
        incomingMapPayload = null;
        incomingMapKey = null;
        if (transfer == null || !key.equals(transferKey)) return;
        if (key.equals(requestedMapKey)) mapRequestPending = false;
        try {
            byte[] payload = transfer.toByteArray();
            if (payload.length != expectedPayloadLength) {
                throw new IllegalArgumentException("Incomplete map transfer");
            }
            byte[] dds = compressed ? inflateBsaMap(payload, expectedDecodedLength) : payload;
            Bitmap decoded = decodeMapImage(new ByteArrayInputStream(dds));
            if (decoded == null) throw new IllegalArgumentException("Unsupported game map DDS");
            if ("MOJAVE".equals(key)) decoded = removeDefaultWorldMapGrid(decoded);
            Bitmap map = decoded;
            runOnUiThread(() -> {
                int tileIndex = localTileIndex(key);
                if (tileIndex >= 0) {
                    incomingLocalTiles[tileIndex] = map;
                    return;
                }
                // Ignore a world-map result if the player switched to a different map during transfer.
                if (!key.equals(currentWorldKey)) return;
                streamedMapCache.put(key, map);
                mapBitmap = map;
                mapLoadStatus = "";
                if (mapCanvas != null) {
                    mapCanvas.resetForMapChange();
                    mapCanvas.invalidate();
                }
            });
        } catch (IOException | DataFormatException | IllegalArgumentException error) {
            runOnUiThread(() -> {
                if (key.equals(currentWorldKey)) {
                    mapLoadStatus = "COULD NOT DECODE GAME MAP";
                    if (mapCanvas != null) mapCanvas.invalidate();
                }
            });
        }
    }

    private byte[] inflateBsaMap(byte[] payload, int decodedLength)
            throws DataFormatException {
        if (payload.length < 5 || decodedLength < 1) {
            throw new IllegalArgumentException("Compressed map data is truncated");
        }
        int declaredLength = (payload[0] & 0xFF) | ((payload[1] & 0xFF) << 8)
                | ((payload[2] & 0xFF) << 16) | ((payload[3] & 0xFF) << 24);
        if (declaredLength != decodedLength) {
            throw new IllegalArgumentException("Compressed map size mismatch");
        }
        // The first four payload bytes declare the decoded size; the zlib stream follows them.
        Inflater inflater = new Inflater();
        inflater.setInput(payload, 4, payload.length - 4);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(decodedLength);
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                } else {
                    decoded.write(buffer, 0, count);
                    if (decoded.size() > decodedLength) {
                        throw new IllegalArgumentException("Inflated map exceeded declared size");
                    }
                }
            }
        } finally {
            inflater.end();
        }
        if (decoded.size() != decodedLength) {
            throw new IllegalArgumentException("Inflated map size mismatch");
        }
        return decoded.toByteArray();
    }

    private String decodeText(String encoded) {
        return new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private String formatTenths(int value) {
        return (value / 10) + "." + Math.abs(value % 10);
    }

    private String formatLimbCondition(int tenths) {
        if (tenths < 0) return "--";
        if (tenths == 0) return "CRIPPLED";
        return formatTenths(tenths) + "%";
    }

    private void requestLimbCare() {
        if (interactionBlocked || dialogueActive || vatsActive || bridgeOutput == null
                || limbTreatmentPending || crippledLimbMask == 0) return;
        playUiSound();
        if (doctorsBagCount == 0) {
            showLimbCarePopup("DOCTOR’S BAG", "No Doctor’s Bag available", 0, -1);
        } else if (cachedHardcore && Integer.bitCount(crippledLimbMask) > 1) {
            showLimbCarePopup("DOCTOR’S BAG", "Which crippled limb would you like to treat?", crippledLimbMask, -1);
        } else {
            confirmLimbCare(Integer.numberOfTrailingZeros(crippledLimbMask));
        }
    }

    private void confirmLimbCare(int limb) {
        if (doctorsBagCount == 0 || crippledLimbMask == 0 || limb < 0 || limb >= 6
                || (cachedHardcore && (crippledLimbMask & (1 << limb)) == 0)) {
            requestLimbCare();
            return;
        }
        showLimbCarePopup("DOCTOR’S BAG", cachedHardcore
                ? "Apply a Doctor’s Bag to your " + LIMB_NAMES[limb].toLowerCase(Locale.ROOT) + "?"
                : "Apply a Doctor’s Bag to restore condition to all limbs?", 0, limb);
    }

    private void showLimbCarePopup(String heading, String message, int pickerMask, int confirmLimb) {
        if (contentDialog != null) contentDialog.dismiss();
        Dialog dialog = new Dialog(this);
        contentDialog = dialog;
        boolean hardcoreAtConfirmation = cachedHardcore;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(outlineDrawable(accentColor, false));
        TextView title = label(heading, 13, true);
        title.setGravity(Gravity.CENTER); card.addView(title, matchWrap());
        TextView text = softLabel(message, 11, false);
        text.setGravity(Gravity.CENTER); text.setPadding(0, dp(12), 0, dp(16));
        card.addView(text, matchWrap());
        for (int i = 0; i < 6; ++i) {
            if ((pickerMask & (1 << i)) == 0) continue;
            final int limb = i;
            TextView choice = dialogAction(LIMB_NAMES[i]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
            params.bottomMargin = dp(5); card.addView(choice, params);
            choice.setOnClickListener(view -> { playUiSound(); dialog.dismiss(); confirmLimbCare(limb); });
        }
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER);
        TextView cancel = dialogAction(confirmLimb >= 0 || pickerMask != 0 ? "CANCEL" : "CLOSE");
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(38), 1));
        cancel.setOnClickListener(view -> { playUiSound(); dialog.dismiss(); });
        if (confirmLimb >= 0) {
            TextView apply = dialogAction("APPLY");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            params.leftMargin = dp(8); actions.addView(apply, params);
            apply.setOnClickListener(view -> {
                if (limbTreatmentPending) return;
                dialog.dismiss();
                if (hardcoreAtConfirmation != cachedHardcore || doctorsBagCount == 0
                        || crippledLimbMask == 0 || (cachedHardcore && (crippledLimbMask & (1 << confirmLimb)) == 0)) {
                    requestLimbCare(); return;
                }
                if (interactionBlocked || dialogueActive || vatsActive || bridgeOutput == null) return;
                playUiSound(); limbTreatmentPending = true;
                sendBridgeCommand("TREATLIMB " + confirmLimb);
            });
        }
        card.addView(actions, matchWrap());
        dialog.setOnDismissListener(ignored -> { if (contentDialog == dialog) contentDialog = null; });
        dialog.setContentView(card); dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(360)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private final class LimbConditionView extends View {
        private final Paint limbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable pulseFrame = this::redrawFrame;
        private String[] values = {"--", "--", "--", "--", "--", "--"};

        LimbConditionView() {
            super(MainActivity.this);
            setContentDescription("Current head, torso, arm, and leg condition values");
            setOnClickListener(view -> requestLimbCare());
        }

        void setValues(String[] current) {
            values = current.clone();
            redrawFrame();
        }

        private void redrawFrame() {
            android.view.ViewParent parent = getParent();
            if (parent instanceof View view) view.invalidate();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float rowHeight = getHeight() / 6.0f;
            limbPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            limbPaint.setTextSize(dp(8));
            boolean pulsing = false;
            for (int i = 0; i < LIMB_NAMES.length; i++) {
                float top = i * rowHeight;
                float baseline = top + dp(9);
                String name = LIMB_NAMES[i];
                String value = values[i];
                float ratio = -1;
                try {
                    if (value.endsWith("%")) ratio = Float.parseFloat(value.substring(0, value.length() - 1)) / 100f;
                } catch (NumberFormatException ignored) { }
                boolean crippled = (crippledLimbMask & (1 << i)) != 0;
                boolean warning = crippled || (ratio >= 0 && ratio < .25f);
                int ink = warning ? Color.rgb(235, 65, 65) : accentColor;
                int alpha = crippled ? 100 + (int) Math.round(155 * (.5 + .5 * Math.cos(
                        android.os.SystemClock.uptimeMillis() % 1400 * (2 * Math.PI / 1400)))) : 255;
                pulsing |= crippled;
                limbPaint.setColor(warning ? ink : secondaryColor); limbPaint.setAlpha(alpha);
                canvas.drawText(name, 0, baseline, limbPaint);
                limbPaint.setColor(ink); limbPaint.setAlpha(alpha);
                limbPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(value, getWidth(), baseline, limbPaint);
                limbPaint.setTextAlign(Paint.Align.LEFT);
                limbPaint.setColor(warning ? ink : panelColor);
                limbPaint.setAlpha(crippled ? Math.max(50, alpha * 2 / 3) : warning ? 64 : 255);
                float barTop = top + dp(12);
                canvas.drawRect(0, barTop, getWidth(), barTop + dp(3), limbPaint);
                limbPaint.setColor(ink); limbPaint.setAlpha(alpha);
                canvas.drawRect(0, barTop, getWidth() * Math.max(0, Math.min(1, ratio)),
                        barTop + dp(3), limbPaint);
            }
            limbPaint.setAlpha(255);
            removeCallbacks(pulseFrame);
            if (pulsing && isShown() && getWindowVisibility() == VISIBLE)
                postDelayed(pulseFrame, 33);
        }

        @Override protected void onVisibilityChanged(View view, int visibility) {
            super.onVisibilityChanged(view, visibility);
            if (pulseFrame != null) {
                removeCallbacks(pulseFrame);
                if (isShown()) redrawFrame();
            }
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (pulseFrame != null) {
                removeCallbacks(pulseFrame);
                if (visibility == VISIBLE) redrawFrame();
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            redrawFrame();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(pulseFrame);
            super.onDetachedFromWindow();
        }
    }

    private final class VaultDoorLoadingView extends View {
        private final Paint doorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();

        VaultDoorLoadingView() {
            super(MainActivity.this);
            setContentDescription("Loading notes");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float radius = Math.min(cx, cy) - dp(2);
            if (radius <= 0) return;
            doorPaint.setColor(accentColor);
            doorPaint.setStyle(Paint.Style.STROKE);
            doorPaint.setStrokeWidth(dp(1.2f));
            canvas.save();
            canvas.rotate((android.os.SystemClock.uptimeMillis() % 1800L) / 5f, cx, cy);
            outline.reset();
            for (int tooth = 0; tooth < 12; tooth++) {
                for (int corner = 0; corner < 4; corner++) {
                    double angle = Math.toRadians(tooth * 30 + (corner < 2 ? 0 : 15));
                    float r = radius * (corner == 1 || corner == 2 ? 1f : 0.82f);
                    float x = cx + (float) Math.cos(angle) * r;
                    float y = cy + (float) Math.sin(angle) * r;
                    if (tooth == 0 && corner == 0) outline.moveTo(x, y);
                    else outline.lineTo(x, y);
                }
            }
            outline.close();
            canvas.drawPath(outline, doorPaint);
            canvas.drawCircle(cx, cy, radius * 0.6f, doorPaint);
            canvas.drawCircle(cx, cy, radius * 0.17f, doorPaint);
            for (int spoke = 0; spoke < 3; spoke++) {
                double angle = Math.toRadians(spoke * 120 - 90);
                float x = (float) Math.cos(angle) * radius;
                float y = (float) Math.sin(angle) * radius;
                canvas.drawLine(cx + x * 0.17f, cy + y * 0.17f,
                        cx + x * 0.6f, cy + y * 0.6f, doorPaint);
            }
            canvas.restore();
            if (isAttachedToWindow() && isShown()) postInvalidateDelayed(33);
        }
    }

    private final class NotePlaybackRulerView extends View {
        private final Paint rulerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long durationMs;
        private long animationStartedAt;

        NotePlaybackRulerView(long durationMs, long elapsedMs) {
            super(MainActivity.this);
            this.durationMs = Math.max(0, durationMs);
            animationStartedAt = android.os.SystemClock.uptimeMillis()
                    - Math.max(0, elapsedMs);
            setContentDescription("Audio note playback position");
        }

        void updatePlayback(long durationMs, long elapsedMs) {
            this.durationMs = Math.max(0, durationMs);
            // Anchor local animation to the latest reported elapsed time between bridge updates.
            animationStartedAt = android.os.SystemClock.uptimeMillis()
                    - Math.max(0, elapsedMs);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredHeight = dp(50);
            setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                    resolveSize(desiredHeight, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) return;

            float inset = dp(2);
            float baseline = height - dp(3);
            float tickSpacing = dp(4);
            long elapsedMs = Math.max(0,
                    android.os.SystemClock.uptimeMillis() - animationStartedAt);
            boolean measuredDuration = durationMs > 0;
            float progress = measuredDuration
                    ? Math.min(1f, (float) elapsedMs / durationMs) : 0f;

            rulerPaint.setColor(accentColor);
            rulerPaint.setTextSize(dp(13));
            rulerPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            rulerPaint.setStyle(Paint.Style.FILL);
            String remainingLabel = "Loading audio length...";
            if (measuredDuration) {
                long remainingTenths = Math.max(0,
                        (durationMs - Math.min(durationMs, elapsedMs) + 99) / 100);
                long minutes = remainingTenths / 600;
                long seconds = (remainingTenths / 10) % 60;
                long tenths = remainingTenths % 10;
                remainingLabel = String.format(java.util.Locale.ROOT,
                        "%02d:%02d:%d remaining", minutes, seconds, tenths);
            }
            rulerPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(remainingLabel, width - inset, dp(15), rulerPaint);
            rulerPaint.setTextAlign(Paint.Align.LEFT);
            rulerPaint.setStyle(Paint.Style.STROKE);
            rulerPaint.setStrokeWidth(dp(1));
            float trackStart = inset;
            float trackEnd = width - inset;
            canvas.drawLine(trackStart, baseline, trackEnd, baseline, rulerPaint);

            int tickCount = (int) ((trackEnd - trackStart) / tickSpacing) + 1;
            for (int i = 0; i <= tickCount; i++) {
                float x = trackStart + i * tickSpacing;
                if (x < trackStart || x > trackEnd) continue;
                boolean major = Math.floorMod(i, 4) == 0;
                float tickTop = baseline - dp(major ? 10 : 5);
                canvas.drawLine(x, tickTop, x, baseline, rulerPaint);
            }

            rulerPaint.setStyle(Paint.Style.FILL);
            float markerWidth = dp(3);
            float markerHeight = dp(10);
            float markerTravel = Math.max(0, trackEnd - trackStart - markerWidth);
            float markerX = trackStart + progress * markerTravel;
            canvas.drawRect(markerX, baseline - markerHeight, markerX + markerWidth,
                    baseline + dp(1), rulerPaint);

            if (isShown() && getWindowVisibility() == View.VISIBLE
                    && (!measuredDuration || progress < 1f)) {
                postInvalidateDelayed(33);
            }
        }
    }

    private void styleActivityCard() {
        String reason = reloadInProgress ? "LOADING_SAVE" : gameActivity;
        boolean floatingAnimation = "BARTERING".equals(reason) || "DEAD".equals(reason)
                || "ENTERING".equals(reason) || "FAST_TRAVEL".equals(reason)
                || "LOADING_SAVE".equals(reason) || "CONTAINER".equals(reason) || "CRAFTING".equals(reason);
        activityCard.setBackground(floatingAnimation ? null : outlineDrawable(accentColor, false));
    }

    private void renderActivityBlock() {
        if (activityOverlay == null) return;
        boolean wasBlocked = interactionBlocked;
        String reason = reloadInProgress ? "LOADING_SAVE" : gameActivity;
        interactionBlocked = companionHasOpened && !"NONE".equals(reason);
        if (interactionBlocked) { dropItemPending = false; limbTreatmentPending = false; }
        activityOverlay.setVisibility(interactionBlocked ? View.VISIBLE : View.GONE);
        styleActivityCard();
        String message;
        switch (reason) {
            case "PAUSED": message = "GAME PAUSED"; break;
            case "LEVEL_UP": message = gameActivityPlace.matches("[0-9]+")
                    ? "WELCOME TO LEVEL " + gameActivityPlace : "WELCOME TO YOUR NEW LEVEL"; break;
            case "LOADING_SAVE": message = "LOADING SAVE"; break;
            case "DEAD": message = "PLAYER HAS DIED"; break;
            case "BARTERING": message = "BARTERING"; break;
            case "FAST_TRAVEL": message = "FAST TRAVELLING"; break;
            case "ENTERING": message = "ENTERING " + (gameActivityPlace.isEmpty()
                    ? "NEW AREA" : gameActivityPlace); break;
            default: message = "LOADING"; break;
        }
        activityMessage.setText(message);
        boolean showRoute = interactionBlocked && "FAST_TRAVEL".equals(reason);
        boolean showHolotape = interactionBlocked && "LOADING_SAVE".equals(reason);
        boolean showDeath = interactionBlocked && "DEAD".equals(reason);
        boolean showCellChange = interactionBlocked && "ENTERING".equals(reason);
        boolean showBarter = interactionBlocked && "BARTERING".equals(reason);
        boolean showLoot = interactionBlocked && "CONTAINER".equals(reason);
        boolean showCrafting = interactionBlocked && "CRAFTING".equals(reason);
        activityCard.setVisibility(showBarter || showLoot ? View.GONE : View.VISIBLE);
        barterSummary.setVisibility(showBarter ? View.VISIBLE : View.GONE);
        lootSummary.setVisibility(showLoot ? View.VISIBLE : View.GONE);
        boolean showContainer = interactionBlocked && "CONTAINER".equals(reason);
        activityMessage.setVisibility(showRoute || showHolotape || showDeath || showCellChange || showBarter
                || showContainer || showCrafting
                ? View.GONE : View.VISIBLE);
        activityRouteTrace.setVisibility(showRoute ? View.VISIBLE : View.GONE);
        if (showRoute) activityRouteTrace.start();
        else activityRouteTrace.stop();
        activityHolotape.setVisibility(showHolotape ? View.VISIBLE : View.GONE);
        activityHolotape.setLoading(showHolotape);
        activityPlayerDeath.setVisibility(showDeath ? View.VISIBLE : View.GONE);
        activityPlayerDeath.setActive(showDeath);
        activityCellChange.setVisibility(showCellChange ? View.VISIBLE : View.GONE);
        activityCellChange.setActive(showCellChange);
        activityContainer.setVisibility(View.GONE);
        activityContainer.setActive(false);
        activityCrafting.setVisibility(showCrafting ? View.VISIBLE : View.GONE);
        activityCrafting.setActive(showCrafting);
        if (interactionBlocked && !wasBlocked) {
            dismissVersionMismatch();
            if (contentDialog != null) { contentDialog.dismiss(); contentDialog = null; }
            if (contentPopup != null) { contentPopup.dismiss(); contentPopup = null; }
            sendBridgeCommand("LOCALOFF");
            if (notesRequested) { notesRequested = false; sendBridgeCommand("NOTESOFF"); }
        } else if (!interactionBlocked && wasBlocked) {
            renderPage();
        }
    }

    private final class RouteTraceView extends View {
        private static final float VIEWPORT = 240;
        private static final float START_X = 28, START_Y = 202;
        private static final float END_X = 212, END_Y = 25;
        private static final float MARKER_RADIUS = 7;
        private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path routePath = new Path();
        private final PathMeasure routeMeasure;
        private final float routeLength;
        private final float[] routePoint = new float[2];
        private final android.graphics.DashPathEffect routeDashes =
                new android.graphics.DashPathEffect(new float[]{1.5f, 8f}, 0);
        private long startedAt;
        private boolean running;

        RouteTraceView() {
            super(MainActivity.this);
            setContentDescription("Fast travelling");
            routePath.moveTo(START_X, START_Y);
            routePath.cubicTo(56, 202, 34, 157, 66, 157);
            routePath.cubicTo(96, 157, 97, 206, 129, 206);
            routePath.cubicTo(164, 206, 145, 156, 178, 156);
            routePath.cubicTo(211, 156, 218, 112, 187, 109);
            routePath.cubicTo(156, 106, 149, 141, 121, 128);
            routePath.cubicTo(91, 115, 49, 126, 48, 95);
            routePath.cubicTo(47, 65, 96, 93, 108, 66);
            routePath.cubicTo(122, 33, 151, 42, 180, 44);
            routePath.cubicTo(203, 46, 190, 25, END_X, END_Y);
            routeMeasure = new PathMeasure(routePath, false);
            routeLength = routeMeasure.getLength();
            routePaint.setColor(accentColor);
            routePaint.setStrokeWidth(1.5f);
            routePaint.setStyle(Paint.Style.STROKE);
            routePaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void start() {
            if (!running) {
                running = true;
                startedAt = android.os.SystemClock.uptimeMillis();
                invalidate();
            }
        }

        void stop() {
            running = false;
            removeCallbacks(frame);
            invalidate();
        }

        private final Runnable frame = () -> invalidate();

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth() / VIEWPORT, getHeight() / VIEWPORT);
            if (scale <= 0) return;
            canvas.save();
            canvas.translate((getWidth() - VIEWPORT * scale) / 2f,
                    (getHeight() - VIEWPORT * scale) / 2f);
            canvas.scale(scale, scale);
            routePaint.setStyle(Paint.Style.STROKE);
            routePaint.setStrokeWidth(3f);
            routePaint.setPathEffect(routeDashes);
            canvas.drawPath(routePath, routePaint);
            routePaint.setPathEffect(null);
            routePaint.setStrokeWidth(2f);
            routePaint.setColor(BACKGROUND);
            routePaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(START_X, START_Y, MARKER_RADIUS, routePaint);
            canvas.drawRect(END_X - MARKER_RADIUS, END_Y - MARKER_RADIUS,
                    END_X + MARKER_RADIUS, END_Y + MARKER_RADIUS, routePaint);
            routePaint.setColor(accentColor);
            routePaint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(START_X, START_Y, MARKER_RADIUS, routePaint);
            canvas.drawRect(END_X - MARKER_RADIUS, END_Y - MARKER_RADIUS,
                    END_X + MARKER_RADIUS, END_Y + MARKER_RADIUS, routePaint);
            if (running && routeLength > 0) {
                float progress = Math.min(1f,
                        (android.os.SystemClock.uptimeMillis() - startedAt) % 3900L / 3600f);
                routeMeasure.getPosTan(routeLength * progress, routePoint, null);
                routePaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(routePoint[0], routePoint[1], 5.5f, routePaint);
                if (isShown() && getWindowVisibility() == View.VISIBLE) {
                    removeCallbacks(frame);
                    postDelayed(frame, 33);
                }
            }
            canvas.restore();
        }

        void updateColor() {
            routePaint.setColor(accentColor);
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }
    }

    private void dismissVersionMismatch() {
        if (versionMismatchDialog != null) {
            versionMismatchDialog.dismiss();
            versionMismatchDialog = null;
        }
    }

    private void showVersionMismatch(String pluginVersion) {
        if (interactionBlocked) return;
        String[] appParts = BuildConfig.VERSION_NAME.split("\\.");
        String[] pluginParts = pluginVersion.split("\\.");
        if (appParts.length != 3 || pluginParts.length != 3 || isFinishing() || isDestroyed()) return;
        int comparison = 0;
        for (int i = 0; i < 3 && comparison == 0; i++) {
            comparison = new java.math.BigInteger(appParts[i])
                    .compareTo(new java.math.BigInteger(pluginParts[i]));
        }
        if (comparison == 0) {
            dismissVersionMismatch();
            return;
        }
        // Version packets arrive once a second. Dismissing this popup must not
        // cause it to reopen until the connection or reported version changes.
        if (!notifiedPluginVersions.add(pluginVersion)) return;
        dismissVersionMismatch();
        boolean pluginOlder = comparison > 0;
        Dialog dialog = new Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(outlineDrawable(accentColor, false));
        TextView title = dialogueLabel("VERSION MISMATCH", 13);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, matchWrap());
        String message = "APP: " + BuildConfig.VERSION_NAME + "\nPLUGIN: " + pluginVersion
                + (pluginOlder ? "\n\nThe plugin is out of date.\nUpdate FNVDS.dll to match the app."
                               : "\n\nThe companion app is out of date.\nUpdate FNVDS.apk to match the plugin.");
        TextView detail = dialogueLabel(message, 11);
        detail.setTextColor(secondaryColor);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(12), 0, dp(16));
        card.addView(detail, matchWrap());
        TextView close = dialogueLabel("CLOSE", 10);
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setBackground(outlineDrawable(accentColor, false));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(120), dp(38));
        closeParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(close, closeParams);
        close.setOnClickListener(view -> { playUiSound(); dialog.dismiss(); });
        dialog.setContentView(card);
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setOnDismissListener(ignored -> {
            if (versionMismatchDialog == dialog) versionMismatchDialog = null;
        });
        versionMismatchDialog = dialog;
        dialog.show();
        if (dialog.getWindow() != null) {
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(360));
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showConnection(String status) {
        runOnUiThread(() -> {
            cachedPlayerLevel = 0;
            cachedLevelExperience = cachedLevelExperienceRequired = "0";
            cachedLevelCapped = false;
            updateExperienceDisplay();
            textEntrySession = 0;
            if (gameKeyboard != null) gameKeyboard.setVisibility(View.GONE);
            companionHasOpened = false;
            reloadInProgress = false;
            gameActivity = "NONE";
            limbTreatmentPending = false;
            doctorsBagCount = 0;
            crippledLimbMask = 0;
            gameActivityPlace = "";
            renderActivityBlock();
            dismissVersionMismatch();
            notifiedPluginVersions.clear();
            gameReadyForNotes = false;
            dialogueActive = false;
            vatsActive = false; vatsState = null; incomingVatsState = null; incomingVatsParts.clear();
            if (vatsOverlay != null) vatsOverlay.setVisibility(View.GONE);
            dialogueSpeechToken = 0;
            dialogueChoices.clear();
            incomingDialogueChoices.clear();
            renderDialogue();
            if (terminalIntro != null) {
                terminalIntro.setPluginConnection("NEW VEGAS LINKED".equals(status));
            }
            if ("NEW VEGAS LINKED".equals(status)) {
                showWaitingIntro(TerminalIntroView.WAITING_FOR_SAVE);
            } else {
                showWaitingIntro(status);
            }
            if (connectionText != null) {
                connectionText.setText(status);
                connectionText.setVisibility("NEW VEGAS LINKED".equals(status) ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void showPipBoy() {
        if (terminalIntro != null && waitingOverlay.getVisibility() == View.VISIBLE) {
            terminalIntro.setStatus(TerminalIntroView.CONNECTED);
        }
    }

    private void showWaitingIntro(String status) {
        if (waitingOverlay == null || terminalIntro == null) return;
        waitingOverlay.animate().cancel();
        waitingOverlay.setAlpha(1f);
        waitingOverlay.setVisibility(View.VISIBLE);
        terminalIntro.setStatus(status);
    }

    private void finishIntro() {
        // READY must still be current when typing finishes; a disconnect cancels the reveal.
        if (!gameReadyForNotes || bridgeOutput == null || waitingOverlay == null) return;
        waitingOverlay.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            if (gameReadyForNotes && bridgeOutput != null) {
                companionHasOpened = true;
                waitingOverlay.setVisibility(View.GONE);
                renderActivityBlock();
            } else {
                waitingOverlay.setAlpha(1f);
            }
        }).start();
    }

    private float mapMarkerPulse() {
        double phase=(android.os.SystemClock.uptimeMillis()%1400L)/1400.0;
        return (float)(0.5-0.5*Math.cos(phase*Math.PI*2));
    }

    private abstract class PulsingMapView extends View {
        private final Runnable pulseFrame=()->{
            if(isShown() && getWindowVisibility()==View.VISIBLE) invalidate();
        };
        PulsingMapView() {super(MainActivity.this);}
        protected void requestPulseFrame() {
            removeCallbacks(pulseFrame);
            if(isShown() && getWindowVisibility()==View.VISIBLE
                    && (waitingOverlay==null || waitingOverlay.getVisibility()!=View.VISIBLE)) {
                postDelayed(pulseFrame,33);
            }
        }
        @Override protected void onDetachedFromWindow() {
            removeCallbacks(pulseFrame);
            super.onDetachedFromWindow();
        }
    }

    private void drawPlayerMapIcon(Canvas canvas,Paint brush,float x,float y,float heading,float zoom,float sizeScale) {
        float pulse=mapMarkerPulse(),scale=sizeScale*(1f+0.10f*pulse)/zoom;
        float w=dp(3f)*scale,h=dp(5f)*scale;
        brush.setColor(accentColor);brush.setAlpha(Math.round(160+95*pulse));
        brush.setStyle(Paint.Style.FILL);
        canvas.save();canvas.rotate((float)Math.toDegrees(heading),x,y);
        Path arrow=new Path();arrow.moveTo(x,y-h);
        arrow.lineTo(x-w,y+h*.86f);arrow.lineTo(x,y+h*.5f);
        arrow.lineTo(x+w,y+h*.86f);arrow.close();canvas.drawPath(arrow,brush);
        canvas.restore();brush.setAlpha(255);
    }

    private void drawDestinationMapIcon(Canvas canvas,Paint brush,float x,float y,boolean quest,float zoom,float sizeScale) {
        float pulse=mapMarkerPulse();
        float scale=sizeScale*(1f+0.08f*pulse)/zoom;
        float w=dp(2.7f)*scale,h=dp(4.7f)*scale;
        Path glyph=new Path();glyph.moveTo(x,y-h);
        glyph.lineTo(x+w,y-h*.35f);glyph.lineTo(x+w,y+h*.8f);
        glyph.lineTo(x,y+h*.55f);glyph.lineTo(x-w,y+h*.8f);
        glyph.lineTo(x-w,y-h*.35f);glyph.close();
        brush.setColor(accentColor);brush.setAlpha(Math.round(160+95*pulse));
        brush.setStrokeWidth(dp(1.1f)*sizeScale/zoom);
        brush.setStyle(quest?Paint.Style.FILL:Paint.Style.STROKE);
        brush.setShadowLayer(dp(2f)/zoom, 0, dp(.7f)/zoom, Color.BLACK);
        canvas.drawPath(glyph,brush);
        brush.clearShadowLayer();
        brush.setAlpha(255);brush.setStyle(Paint.Style.FILL);
    }

    private float[] clipRouteRange(float x, float y, float dx, float dy, RectF bounds) {
        float low=0, high=1;
        float[] p={-dx,dx,-dy,dy}, q={x-bounds.left,bounds.right-x,y-bounds.top,bounds.bottom-y};
        for(int i=0;i<4;i++) {
            if(p[i]==0) { if(q[i]<0) return null; }
            else {
                float t=q[i]/p[i];
                if(p[i]<0) low=Math.max(low,t); else high=Math.min(high,t);
                if(low>high) return null;
            }
        }
        return new float[]{low,high};
    }

    private void drawDestinationOverlay(Canvas canvas, Paint brush, float x, float y,
                                        boolean quest, float scale, int width, int height) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || width <= 0 || height <= 0) return;
        float centerX = width/2f, centerY = height/2f;
        float margin = dp(12f) * scale;
        float dx = x-centerX, dy = y-centerY;
        float edgeX = Math.max(0, centerX-margin), edgeY = Math.max(0, centerY-margin);
        float ratio = 1f;
        if (Math.abs(dx) > edgeX) ratio = Math.min(ratio, edgeX/Math.abs(dx));
        if (Math.abs(dy) > edgeY) ratio = Math.min(ratio, edgeY/Math.abs(dy));
        drawDestinationMapIcon(canvas, brush, centerX+dx*ratio, centerY+dy*ratio, quest, 1f, scale);
    }

    private void confirmCustomMarker(String command, float x, float y,
                                     java.util.function.BooleanSupplier sameMap) {
        boolean canPlace = Float.isFinite(x) && Float.isFinite(y) && x>=0 && x<=1 && y>=0 && y<=1;
        if (interactionBlocked || dialogueActive || vatsActive || bridgeOutput == null
                || selectedSection != 2 || selectedSubsection != 0
                || (!canPlace && !customMarkerExists) || !sameMap.getAsBoolean()) return;
        if (contentDialog != null && contentDialog.isShowing()) return;
        Dialog dialog = new Dialog(MainActivity.this);
        contentDialog = dialog;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18),dp(16),dp(18),dp(14));
        card.setBackground(outlineDrawable(accentColor,false));
        TextView title = label("CUSTOM MARKER",13,true);
        title.setGravity(Gravity.CENTER);card.addView(title,matchWrap());
        TextView message = softLabel(!canPlace ? "Remove the existing custom marker?"
                : customMarkerExists ? "Place a marker at the center cursor, or remove the existing marker?"
                : "Place a custom marker at the center cursor?",11,false);
        message.setGravity(Gravity.CENTER);message.setPadding(0,dp(12),0,dp(16));
        card.addView(message,matchWrap());
        LinearLayout actions = new LinearLayout(this);
        TextView cancel = dialogAction("CANCEL"), place = dialogAction("PLACE");
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0,dp(38),1);
        cancelParams.rightMargin=dp(8);actions.addView(cancel,cancelParams);
        if (canPlace) actions.addView(place,new LinearLayout.LayoutParams(0,dp(38),1));
        cancel.setOnClickListener(view -> dialog.dismiss());
        place.setOnClickListener(view -> {
            if (!interactionBlocked && !dialogueActive && !vatsActive && selectedSection == 2
                    && selectedSubsection == 0 && sameMap.getAsBoolean()) sendBridgeCommand(command);
            dialog.dismiss();
        });
        card.addView(actions,matchWrap());
        if (customMarkerExists) {
            TextView remove = dialogAction("REMOVE MARKER");
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,dp(38));
            removeParams.topMargin=dp(8);
            card.addView(remove,removeParams);
            remove.setOnClickListener(view -> {
                if (!interactionBlocked && !dialogueActive && !vatsActive && selectedSection == 2
                        && selectedSubsection == 0 && sameMap.getAsBoolean() && customMarkerExists)
                    sendBridgeCommand("REMOVECUSTOM");
                dialog.dismiss();
            });
        }
        dialog.setContentView(card);dialog.show();
        if(dialog.getWindow()!=null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(360)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private final class LocalMapCanvas extends PulsingMapView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private float zoom = 1.3f, panX, panY, touchX, touchY;
        private float pinchFocusX, pinchFocusY;
        private final ScaleGestureDetector pinch;
        private final android.view.GestureDetector taps;
        private boolean markerTapMoved;
        private float markerDownX, markerDownY;
        LocalMapCanvas() {
            super();
            taps = new android.view.GestureDetector(MainActivity.this,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(MotionEvent event) { return true; }
                        @Override public boolean onDoubleTapEvent(MotionEvent event) {
                            if (event.getActionMasked() == MotionEvent.ACTION_UP && !markerTapMoved) confirmLocalMarker();
                            return true;
                        }
                    });
            pinch = new ScaleGestureDetector(MainActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                            pinchFocusX = detector.getFocusX();
                            pinchFocusY = detector.getFocusY();
                            return true;
                        }
                        @Override public boolean onScale(ScaleGestureDetector detector) {
                            float nextZoom = Math.max(0.7f, Math.min(6f, zoom * detector.getScaleFactor()));
                            float ratio = nextZoom / zoom;
                            float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
                            // Preserve the map point under the pinch while its focus moves.
                            panX = detector.getFocusX() - centerX - (pinchFocusX - centerX - panX) * ratio;
                            panY = detector.getFocusY() - centerY - (pinchFocusY - centerY - panY) * ratio;
                            zoom = nextZoom;
                            pinchFocusX = detector.getFocusX();
                            pinchFocusY = detector.getFocusY();
                            invalidate(); return true;
                        }
                    });
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(BACKGROUND);
            if (localMapBitmap == null) {
                paint.setColor(accentColor); paint.setTextSize(dp(12));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(localStatus, getWidth()/2f, getHeight()/2f, paint);
                return;
            }
            float size = Math.min(getWidth(), getHeight()) - dp(12);
            float left = (getWidth()-size)/2f, top = (getHeight()-size)/2f;
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            canvas.translate(panX, panY);
            canvas.scale(zoom, zoom, getWidth()/2f, getHeight()/2f);
            RectF rect = new RectF(left, top, left+size, top+size);
            paint.setColorFilter(new PorterDuffColorFilter(accentColor, PorterDuff.Mode.MULTIPLY));
            canvas.drawBitmap(localMapBitmap, null, rect, paint);
            paint.setColorFilter(null);
            drawLocalDestinationLines(canvas,rect);
            MapMarkerEntry hovered=drawLocalMarkers(canvas,rect);
            if (localPositionGeneration == localMapGeneration && Float.isFinite(localPlayerX)
                    && localPlayerX >= 0 && localPlayerX <= 1 && localPlayerY >= 0 && localPlayerY <= 1) {
                float x = left + localPlayerX*size, y = top + localPlayerY*size;
                drawPlayerMapIcon(canvas,paint,x,y,localHeading,zoom,1f);
            }
            canvas.restore();
            if (localMarkerArea == localMapArea && localMarkerCenterX == localCenterX
                    && localMarkerCenterY == localCenterY) {
                for (MapMarkerEntry marker : localMarkers) {
                    if (marker.type != 'Q' && marker.type != 'C') continue;
                    float sx = getWidth()/2f + zoom*(left + marker.x*size - getWidth()/2f) + panX;
                    float sy = getHeight()/2f + zoom*(top + marker.y*size - getHeight()/2f) + panY;
                    drawDestinationOverlay(canvas, paint, sx, sy, marker.type == 'Q', 1f, getWidth(), getHeight());
                }
            }
            paint.setColor(accentColor);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));float cursorX=getWidth()/2f,cursorY=getHeight()/2f;
            canvas.drawCircle(cursorX,cursorY,dp(7),paint);
            canvas.drawLine(cursorX-dp(11),cursorY,cursorX+dp(11),cursorY,paint);
            canvas.drawLine(cursorX,cursorY-dp(11),cursorX,cursorY+dp(11),paint);
            paint.setStyle(Paint.Style.FILL);
            if(hovered!=null) {
                paint.setTextSize(dp(11));paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.MONOSPACE);
                paint.setShadowLayer(dp(2),0,0,Color.BLACK);
                canvas.drawText(hovered.name.toUpperCase(java.util.Locale.ROOT),getWidth()/2f,dp(20),paint);
                paint.clearShadowLayer();
            }
            requestPulseFrame();
        }
        private void drawLocalDestinationLines(Canvas canvas, RectF rect) {
            if(localPositionGeneration!=localMapGeneration || !Float.isFinite(localPlayerX)
                    || !Float.isFinite(localPlayerY) || localPlayerX<0 || localPlayerX>1
                    || localPlayerY<0 || localPlayerY>1 || localMarkerArea!=localMapArea
                    || localMarkerCenterX!=localCenterX || localMarkerCenterY!=localCenterY) return;
            paint.setColor(accentColor);paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(Math.round(160+95*mapMarkerPulse()));
            float startX=rect.left+localPlayerX*rect.width();
            float startY=rect.top+localPlayerY*rect.height();
            // Counter-scale both spacing and radius to match the world map's
            // constant screen-size dots while panning and zooming.
            float step=dp(8)/zoom,radius=dp(1.15f)/zoom;
            for(MapMarkerEntry marker:localMarkers) {
                if(marker.type!='Q' && marker.type!='C') continue;
                if(!Float.isFinite(marker.x) || !Float.isFinite(marker.y)) continue;
                float dx=rect.left+marker.x*rect.width()-startX;
                float dy=rect.top+marker.y*rect.height()-startY;
                float length=(float)Math.hypot(dx,dy);
                if (length <= step) continue;
                RectF visible = new RectF(canvas.getClipBounds());
                float[] range = clipRouteRange(startX, startY, dx, dy, visible);
                if (range == null) continue;
                float first = Math.max(step, (float)Math.ceil(range[0]*length/step)*step);
                float end = Math.min(length-step, range[1]*length);
                for(float distance=first;distance<end;distance+=step) {
                    float fraction=distance/length;
                    canvas.drawCircle(startX+dx*fraction,startY+dy*fraction,radius,paint);
                }
            }
            paint.setAlpha(255);
        }
        private MapMarkerEntry drawLocalMarkers(Canvas canvas, RectF rect) {
            if(localMarkerArea!=localMapArea || localMarkerCenterX!=localCenterX
                    || localMarkerCenterY!=localCenterY) return null;
            MapMarkerEntry hovered=null;float nearest=dp(22);
            // Draw door symbols before destination pins when they share a point.
            for(int pass=0;pass<2;pass++) for(MapMarkerEntry marker:localMarkers) {
                if((marker.type=='D')!=(pass==0)) continue;
                if(marker.x<0 || marker.x>1 || marker.y<0 || marker.y>1) continue;
                float x=rect.left+marker.x*rect.width(),y=rect.top+marker.y*rect.height();
                paint.setColor(accentColor);paint.setStrokeWidth(dp(1.1f)/zoom);
                float w=dp(2.7f)/zoom,h=dp(4.7f)/zoom;
                if(marker.type=='D') {
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(x-w,y-h,x+w,y+h,paint);
                    canvas.drawLine(x-w*.2f,y-h*.55f,x-w*.2f,y+h*.55f,paint);
                    Path exit=new Path();exit.moveTo(x-w*.2f,y);exit.lineTo(x+w*1.6f,y);
                    exit.moveTo(x+w*.7f,y-h*.3f);exit.lineTo(x+w*1.6f,y);exit.lineTo(x+w*.7f,y+h*.3f);
                    canvas.drawPath(exit,paint);
                }
                float sx=getWidth()/2f+zoom*(x-getWidth()/2f)+panX;
                float sy=getHeight()/2f+zoom*(y-getHeight()/2f)+panY;
                float distance=(float)Math.hypot(sx-getWidth()/2f,sy-getHeight()/2f);
                if(distance<nearest) {nearest=distance;hovered=marker;}
            }
            paint.setStyle(Paint.Style.FILL);return hovered;
        }

        private void confirmLocalMarker() {
            if (localMapBitmap == null || localPositionGeneration != localMapGeneration) return;
            float size = Math.min(getWidth(), getHeight()) - dp(12);
            if (size <= 0) return;
            float x = .5f - panX / (zoom * size), y = .5f - panY / (zoom * size);
            long area = localMapArea;
            int cx = localCenterX, cy = localCenterY;
            confirmCustomMarker(String.format(Locale.ROOT, "PLACELOCAL %d %d %d %.7f %.7f", area, cx, cy, x, y),
                    x, y, () -> localMapCanvas == this && localMapSelected && area == localMapArea
                            && cx == localCenterX && cy == localCenterY);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                markerTapMoved = false; markerDownX=event.getX(); markerDownY=event.getY();
            } else if (event.getPointerCount()>1 || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP
                    || Math.hypot(event.getX()-markerDownX,event.getY()-markerDownY)>dp(7)) {
                markerTapMoved = true;
            }
            pinch.onTouchEvent(event);
            taps.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchX=event.getX(); touchY=event.getY();
                getParent().requestDisallowInterceptTouchEvent(true); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                touchX = event.getX(); touchY = event.getY();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                touchX = event.getX(remaining); touchY = event.getY(remaining);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (!pinch.isInProgress() && event.getPointerCount()==1) {
                    panX+=event.getX()-touchX; panY+=event.getY()-touchY; invalidate();
                }
                touchX=event.getX(); touchY=event.getY(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private final class MapCanvas extends PulsingMapView {
        private PointF searchFocus;
        private String searchFocusWorld;
        private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaleDetector;
        private final android.view.GestureDetector taps;
        private float zoom = 1.25f;
        private float pinchFocusX, pinchFocusY;
        private float panX;
        private float panY;
        private float lastTouchX;
        private float lastTouchY;
        private float downTouchX;
        private float downTouchY;
        private boolean dragging;
        private boolean longPressHandled;
        private final Runnable longPressAction = () -> {
            if(dragging) return;
            MapMarkerEntry marker=findMarkerAtCursor();
            if(marker!=null) {
                longPressHandled=true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                showFavouriteAction(marker);
            }
        };

        private void showLocationSearch() {
            if (interactionBlocked || dialogueActive || vatsActive) return;
            if (contentDialog != null) contentDialog.dismiss();
            String searchWorld = currentWorldKey;
            MapSearchDialog dialog = new MapSearchDialog(MainActivity.this, accentColor,
                    secondaryColor, panelColor, BACKGROUND, MainActivity.this::playUiSound, location -> {
                if (interactionBlocked || dialogueActive || vatsActive || mapCanvas != this
                        || selectedSection != 2 || selectedSubsection != 0 || localMapSelected
                        || !searchWorld.equals(currentWorldKey)) return;
                for (MapMarkerEntry marker : mapMarkers) {
                    if (marker.refId == location.id() && validMapPoint(marker.x, marker.y)) {
                        searchFocus = new PointF(marker.x, marker.y);
                        searchFocusWorld = searchWorld;
                        initialViewSet = false;
                        invalidate();
                        break;
                    }
                }
            });
            mapSearchDialog = dialog;
            contentDialog = dialog;
            dialog.setOnDismissListener(ignored -> {
                if (mapSearchDialog == dialog) mapSearchDialog = null;
                if (contentDialog == dialog) contentDialog = null;
            });
            updateMapSearchLocations();
            dialog.show();
        }

        private String favouriteKey() { return "map_favourites_"+currentWorldKey; }

        private java.util.Set<String> favouriteEntries() {
            // Return a copy so callers do not modify the set returned by SharedPreferences.
            return new java.util.HashSet<>(getPreferences(MODE_PRIVATE)
                    .getStringSet(favouriteKey(),java.util.Collections.emptySet()));
        }

        private String favouriteRecord(MapMarkerEntry marker) {
            // Store an unsigned form ID and a Base64-encoded UTF-8 name separated by a colon.
            return Long.toUnsignedString(marker.refId)+":"+
                    Base64.encodeToString(marker.name.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        }

        private boolean recordMatches(String record,long id) {
            return record.startsWith(Long.toUnsignedString(id)+":");
        }

        private void showFavouriteAction(MapMarkerEntry marker) {
            java.util.Set<String> entries=favouriteEntries();
            boolean saved=false;
            for(String record:entries) if(recordMatches(record,marker.refId)) saved=true;
            final boolean remove=saved;
            final String key=favouriteKey();
            Dialog dialog=new Dialog(MainActivity.this);
            contentDialog = dialog;
            LinearLayout card=new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16),dp(16),dp(16),dp(16));
            card.setBackground(outlineDrawable(accentColor,false));
            TextView title=label(marker.name,13,true);
            card.addView(title,matchWrap());
            TextView action=dialogAction(remove ? "REMOVE FROM FAVOURITES" : "ADD TO FAVOURITES");
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,dp(44));
            params.topMargin=dp(12);
            card.addView(action,params);
            action.setOnClickListener(view->{
                java.util.Set<String> updated=new java.util.HashSet<>(getPreferences(MODE_PRIVATE)
                        .getStringSet(key,java.util.Collections.emptySet()));
                updated.removeIf(record->recordMatches(record,marker.refId));
                if(!remove) updated.add(favouriteRecord(marker));
                getPreferences(MODE_PRIVATE).edit().putStringSet(key,updated).apply();
                dialog.dismiss();
            });
            TextView cancel=dialogAction("CANCEL");
            card.addView(cancel,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(44)));
            cancel.setOnClickListener(view->dialog.dismiss());
            dialog.setContentView(card);
            dialog.show();
            if(dialog.getWindow()!=null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(360)),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }

        private void showFavourites(View anchor) {
            LinearLayout menu=new LinearLayout(MainActivity.this);
            menu.setOrientation(LinearLayout.VERTICAL);
            menu.setPadding(dp(12),dp(10),dp(12),dp(10));
            menu.setBackground(outlineDrawable(accentColor,false));
            TextView heading=label("FAVOURITES",12,true);
            menu.addView(heading,matchWrap());
            ScrollView scroll=new ScrollView(MainActivity.this);
            LinearLayout rows=new LinearLayout(MainActivity.this);
            rows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rows,matchWrap());
            menu.addView(scroll,matchWrap());
            java.util.List<String> records=new ArrayList<>(favouriteEntries());
            records.sort((a,b)->decodeText(a.substring(a.indexOf(':')+1))
                    .compareToIgnoreCase(decodeText(b.substring(b.indexOf(':')+1))));
            android.widget.PopupWindow popup=new android.widget.PopupWindow(menu,
                    Math.min(dp(320),getResources().getDisplayMetrics().widthPixels-dp(32)),
                    ViewGroup.LayoutParams.WRAP_CONTENT,true);
            contentPopup = popup;
            popup.setBackgroundDrawable(outlineDrawable(accentColor,false));
            popup.setOutsideTouchable(true);
            if(records.isEmpty()) {
                TextView empty=label("No favourites on this map",11,false);
                empty.setPadding(0,dp(12),0,dp(8));
                rows.addView(empty,matchWrap());
            }
            for(String record:records) {
                int split=record.indexOf(':');
                if(split<0) continue;
                final long id;
                try { id=Long.parseUnsignedLong(record.substring(0,split)); }
                catch(NumberFormatException ignored) { continue; }
                String name=decodeText(record.substring(split+1));
                TextView entry=dialogAction(name);
                entry.setPadding(dp(8),dp(10),dp(8),dp(10));
                entry.setMinHeight(dp(44));
                rows.addView(entry,matchWrap());
                entry.setOnClickListener(view->{
                    popup.dismiss();
                    MapMarkerEntry live=null;
                    for(MapMarkerEntry marker:mapMarkers) if(marker.refId==id) {live=marker;break;}
                    // Resolve against the current save, never trust saved travel eligibility.
                    showFastTravelConfirmation(live!=null ? live
                            : new MapMarkerEntry(0,false,0,0,id,name));
                });
                entry.setOnLongClickListener(view->{
                    popup.dismiss();
                    showFavouriteAction(new MapMarkerEntry(0,false,0,0,id,name));
                    return true;
                });
            }
            if(records.size()>4) scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,dp(200)));
            popup.showAsDropDown(anchor,0,dp(4),Gravity.RIGHT);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(longPressAction);
            super.onDetachedFromWindow();
        }
        private boolean initialViewSet;
        private MapMarkerEntry hoveredMarker;

        MapCanvas() {
            super();
            setBackgroundColor(BACKGROUND);
            // Avoid intermittent hardware-texture seams while the map is
            // scaled/panned and when Android captures the surface for recents.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            mapPaint.setDither(true);
            mapPaint.setFilterBitmap(true);
            routePaint.setStyle(Paint.Style.FILL);
            taps = new android.view.GestureDetector(MainActivity.this,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(MotionEvent event) { return true; }
                        @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                            if (!dragging && !longPressHandled && !interactionBlocked) {
                                MapMarkerEntry marker = findMarkerAtCursor();
                                if (marker != null) showFastTravelConfirmation(marker);
                            }
                            return true;
                        }
                        @Override public boolean onDoubleTapEvent(MotionEvent event) {
                            if (event.getActionMasked() == MotionEvent.ACTION_UP && !dragging && !longPressHandled)
                                confirmWorldMarker();
                            return true;
                        }
                    });
            scaleDetector = new ScaleGestureDetector(MainActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                            pinchFocusX = detector.getFocusX();
                            pinchFocusY = detector.getFocusY();
                            removeCallbacks(longPressAction);
                            dragging = true;
                            return true;
                        }
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            float nextZoom = Math.max(1.25f, Math.min(8.0f,
                                    zoom * detector.getScaleFactor()));
                            float ratio = nextZoom / zoom;
                            float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
                            // Canvas draws around the view center, with pan in screen pixels.
                            panX = detector.getFocusX() - centerX - (pinchFocusX - centerX - panX) * ratio;
                            panY = detector.getFocusY() - centerY - (pinchFocusY - centerY - panY) * ratio;
                            zoom = nextZoom;
                            pinchFocusX = detector.getFocusX();
                            pinchFocusY = detector.getFocusY();
                            invalidate();
                            return true;
                        }
                    });
        }

        void resetForMapChange() {
            zoom = 1.25f;
            panX = 0.0f;
            panY = 0.0f;
            initialViewSet = false;
            hoveredMarker = null;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(BACKGROUND, PorterDuff.Mode.SRC);
            if (mapBitmap == null) {
                markerPaint.setColor(accentColor);
                markerPaint.setTextAlign(Paint.Align.CENTER);
                markerPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                markerPaint.setTextSize(dp(13));
                canvas.drawText(mapLoadStatus, getWidth() / 2.0f,
                        getHeight() / 2.0f - dp(8), markerPaint);
                markerPaint.setColor(secondaryColor);
                markerPaint.setTextSize(dp(10));
                canvas.drawText("Map textures stream from New Vegas",
                        getWidth() / 2.0f, getHeight() / 2.0f + dp(13), markerPaint);
                if (Float.isFinite(mapPlayerX) && Float.isFinite(mapPlayerY)) {
                    canvas.drawText("PLAYER POSITION LINKED", getWidth() / 2.0f,
                            getHeight() / 2.0f + dp(32), markerPaint);
                }
                return;
            }

            RectF mapRect = fittedMapRect();
            setInitialPlayerView(mapRect);
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            canvas.translate(panX, panY);
            canvas.scale(zoom, zoom, getWidth() / 2.0f, getHeight() / 2.0f);
            mapPaint.setColorFilter(new PorterDuffColorFilter(accentColor, PorterDuff.Mode.MULTIPLY));
            canvas.drawBitmap(mapBitmap, null, mapRect, mapPaint);
            mapPaint.setColorFilter(null);
            canvas.clipRect(mapRect);

            drawDestinationRoutes(canvas, mapRect);

            MapMarkerEntry selectedMarker = null;
            float selectedMarkerDistance = dp(22);
            float cursorX = getWidth() / 2.0f;
            float cursorY = getHeight() / 2.0f;
            for (MapMarkerEntry marker : mapMarkers) {
                if (marker.x < 0 || marker.x > 1 || marker.y < 0 || marker.y > 1) continue;
                float x = pointScreenX(marker.x, mapRect);
                float y = pointScreenY(marker.y, mapRect);
                markerPaint.setColor(accentColor);
                float markerScale = worldMarkerSizeScale();
                float halfMarker = dp(3.0f) * markerScale / zoom;
                markerPaint.setStrokeWidth(dp(1.0f) * markerScale / zoom);
                markerPaint.setStyle(marker.canTravel ? Paint.Style.FILL : Paint.Style.STROKE);
                canvas.drawRect(x - halfMarker, y - halfMarker,
                        x + halfMarker, y + halfMarker, markerPaint);
                float dx = screenX(marker.x, mapRect) - cursorX;
                float dy = screenY(marker.y, mapRect) - cursorY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance < selectedMarkerDistance) {
                    selectedMarkerDistance = distance;
                    selectedMarker = marker;
                }
            }
            hoveredMarker = selectedMarker;
            boolean viewedQuestTracked = false;
            for (QuestEntry entry : questEntries) {
                if (entry.tracked && entry.questFormId == viewedQuestForm) {
                    viewedQuestTracked = true;
                    break;
                }
            }
            if (Float.isFinite(mapPlayerX) && Float.isFinite(mapPlayerY)
                    && mapPlayerX >= 0 && mapPlayerX <= 1 && mapPlayerY >= 0 && mapPlayerY <= 1) {
                float x = pointScreenX(mapPlayerX, mapRect);
                float y = pointScreenY(mapPlayerY, mapRect);
                drawPlayerMapIcon(canvas,markerPaint,x,y,mapPlayerHeading,zoom,worldMarkerSizeScale());
            }
            canvas.restore();
            for (QuestDestination destination : questDestinations) {
                drawDestinationIcon(canvas, mapRect, destination.x, destination.y, true);
            }
            if (viewedQuestDestination != null && !viewedQuestTracked) {
                drawDestinationIcon(canvas, mapRect, viewedQuestDestination.x, viewedQuestDestination.y, true);
            }
            drawDestinationIcon(canvas, mapRect, customMapX, customMapY, false);
            drawHoveredLocationLabel(canvas, mapRect, hoveredMarker);
            drawCenterCursor(canvas, cursorX, cursorY);
            requestPulseFrame();
        }

        private float worldMarkerSizeScale() {
            // Preserve the initial-view size; double the on-screen size at maximum zoom.
            return 1f + Math.max(0f, Math.min(1f, (zoom - 2.2f) / (8f - 2.2f)));
        }

        private boolean validMapPoint(float x, float y) {
            return Float.isFinite(x) && Float.isFinite(y)
                    && x >= 0 && x <= 1 && y >= 0 && y <= 1;
        }

        private float pointScreenX(float x, RectF rect) {
            return rect.left + x * rect.width();
        }

        private float pointScreenY(float y, RectF rect) {
            return rect.top + y * rect.height();
        }

        private void drawDestinationRoutes(Canvas canvas, RectF rect) {
            if (!validMapPoint(mapPlayerX, mapPlayerY)) return;
            for (QuestDestination destination : questDestinations) {
                if (!validMapPoint(destination.x, destination.y)) continue;
                Path route = new Path();
                route.moveTo(pointScreenX(mapPlayerX, rect), pointScreenY(mapPlayerY, rect));
                for (PointF waypoint : destination.route) {
                    if (validMapPoint(waypoint.x, waypoint.y)) {
                        route.lineTo(pointScreenX(waypoint.x, rect), pointScreenY(waypoint.y, rect));
                    }
                }
                route.lineTo(pointScreenX(destination.x, rect), pointScreenY(destination.y, rect));
                drawDottedRoute(canvas, route);
            }
            if (validMapPoint(customMapX, customMapY)) {
                Path route = new Path();
                route.moveTo(pointScreenX(mapPlayerX, rect), pointScreenY(mapPlayerY, rect));
                route.lineTo(pointScreenX(customMapX, rect), pointScreenY(customMapY, rect));
                drawDottedRoute(canvas, route);
            }
        }

        private void drawDottedRoute(Canvas canvas, Path route) {
            routePaint.setColor(accentColor);
            routePaint.setStyle(Paint.Style.FILL);
            routePaint.setAlpha(Math.round(160+95*mapMarkerPulse()));
            PathMeasure measure = new PathMeasure(route, false);
            float length = measure.getLength();
            float step = dp(8) / zoom;
            float radius = dp(1.15f) / zoom;
            float[] point = new float[2];
            // Leave room at either end so dots don't obscure the icons.
            for (float distance = step; distance < length - step; distance += step) {
                if (measure.getPosTan(distance, point, null)) {
                    canvas.drawCircle(point[0], point[1], radius, routePaint);
                }
            }
        }

        private void drawDestinationIcon(Canvas canvas, RectF rect, float x, float y,
                                         boolean questMarker) {
            if (!validMapPoint(x, y)) return;
            drawDestinationOverlay(canvas,markerPaint,screenX(x,rect),screenY(y,rect),
                    questMarker,worldMarkerSizeScale(),getWidth(),getHeight());
        }

        private void setInitialPlayerView(RectF mapRect) {
            if (searchFocus != null && !currentWorldKey.equals(searchFocusWorld)) searchFocus = null;
            if (!initialViewSet && searchFocus != null) {
                zoom = 3.2f;
                panX = -zoom * (pointScreenX(searchFocus.x, mapRect) - getWidth() / 2f);
                panY = -zoom * (pointScreenY(searchFocus.y, mapRect) - getHeight() / 2f);
                searchFocus = null;
                initialViewSet = true;
                return;
            }
            if (!initialViewSet && viewedQuestDestination != null) {
                zoom = 3.2f;
                panX = -zoom * (pointScreenX(viewedQuestDestination.x, mapRect) - getWidth() / 2f);
                panY = -zoom * (pointScreenY(viewedQuestDestination.y, mapRect) - getHeight() / 2f);
                initialViewSet = true;
                return;
            }
            if (initialViewSet || !Float.isFinite(mapPlayerX) || !Float.isFinite(mapPlayerY)
                    || mapPlayerX < 0 || mapPlayerX > 1 || mapPlayerY < 0 || mapPlayerY > 1) return;
            zoom = 2.2f;
            float playerX = pointScreenX(mapPlayerX, mapRect);
            float playerY = pointScreenY(mapPlayerY, mapRect);
            panX = -zoom * (playerX - getWidth() / 2.0f);
            panY = -zoom * (playerY - getHeight() / 2.0f);
            initialViewSet = true;
        }

        private void drawHoveredLocationLabel(Canvas canvas, RectF mapRect,
                                              MapMarkerEntry marker) {
            if (marker == null || marker.name == null || marker.name.isEmpty()) return;
            markerPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            markerPaint.setTextSize(dp(11));
            markerPaint.setTextAlign(Paint.Align.CENTER);
            markerPaint.setStyle(Paint.Style.FILL);
            String label = marker.name.toUpperCase(java.util.Locale.ROOT);
            markerPaint.setColor(accentColor);
            markerPaint.setShadowLayer(dp(2), 0, 0, Color.BLACK);
            canvas.drawText(label, mapRect.centerX(), mapRect.top + dp(18), markerPaint);
            markerPaint.clearShadowLayer();
        }

        private void drawCenterCursor(Canvas canvas, float x, float y) {
            markerPaint.setColor(accentColor);
            markerPaint.setStyle(Paint.Style.STROKE);
            markerPaint.setStrokeWidth(dp(1.4f));
            float radius = dp(8);
            canvas.drawCircle(x, y, radius, markerPaint);
            canvas.drawLine(x - dp(13), y, x - radius - dp(2), y, markerPaint);
            canvas.drawLine(x + radius + dp(2), y, x + dp(13), y, markerPaint);
            canvas.drawLine(x, y - dp(13), x, y - radius - dp(2), markerPaint);
            canvas.drawLine(x, y + radius + dp(2), x, y + dp(13), markerPaint);
            markerPaint.setStyle(Paint.Style.FILL);
        }

        private MapMarkerEntry findMarkerAtCursor() {
            if (mapBitmap == null) return null;
            RectF rect = fittedMapRect();
            MapMarkerEntry nearest = null;
            float nearestDistance = dp(22);
            float cursorX = getWidth() / 2.0f;
            float cursorY = getHeight() / 2.0f;
            for (MapMarkerEntry marker : mapMarkers) {
                if (!validMapPoint(marker.x, marker.y)) continue;
                float dx = screenX(marker.x, rect) - cursorX;
                float dy = screenY(marker.y, rect) - cursorY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = marker;
                }
            }
            return nearest;
        }

        private void showFastTravelConfirmation(MapMarkerEntry marker) {
            boolean available = marker.canTravel && marker.refId != 0;
            Dialog dialog = new Dialog(MainActivity.this);
            contentDialog = dialog;
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(16), dp(18), dp(14));
            card.setBackground(outlineDrawable(accentColor, false));

            TextView title = label(available ? "FAST TRAVEL" : "LOCATION UNAVAILABLE", 13, true);
            title.setGravity(Gravity.CENTER);
            card.addView(title, matchWrap());

            TextView message = softLabel(available
                    ? "Travel to " + marker.name + "?"
                    : marker.name + " has not been discovered for fast travel.", 11, false);
            message.setGravity(Gravity.CENTER);
            message.setPadding(0, dp(12), 0, dp(16));
            card.addView(message, matchWrap());

            LinearLayout actions = new LinearLayout(MainActivity.this);
            actions.setGravity(Gravity.CENTER);
            TextView cancel = dialogAction(available ? "CANCEL" : "CLOSE");
            LinearLayout.LayoutParams actionParams = available
                    ? new LinearLayout.LayoutParams(0, dp(38), 1)
                    : new LinearLayout.LayoutParams(dp(120), dp(38));
            if (available) actionParams.rightMargin = dp(8);
            actions.addView(cancel, actionParams);
            cancel.setOnClickListener(view -> dialog.dismiss());
            if (available) {
                TextView travel = dialogAction("TRAVEL");
                actions.addView(travel, new LinearLayout.LayoutParams(0, dp(38), 1));
                travel.setOnClickListener(view -> {
                    sendBridgeCommand("FASTTRAVEL " + Long.toUnsignedString(marker.refId));
                    dialog.dismiss();
                });
            }
            card.addView(actions, matchWrap());
            dialog.setContentView(card);
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32),
                        dp(360));
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }

        private RectF fittedMapRect() {
            float availableWidth = Math.max(1, getWidth() - dp(12));
            float availableHeight = Math.max(1, getHeight() - dp(12));
            float scale = Math.min(availableWidth / mapBitmap.getWidth(),
                    availableHeight / mapBitmap.getHeight());
            float width = mapBitmap.getWidth() * scale;
            float height = mapBitmap.getHeight() * scale;
            float left = (getWidth() - width) / 2.0f;
            float top = (getHeight() - height) / 2.0f;
            return new RectF(left, top, left + width, top + height);
        }

        private float screenX(float mapX, RectF rect) {
            float imageX = pointScreenX(mapX, rect);
            return getWidth() / 2.0f + zoom * (imageX - getWidth() / 2.0f) + panX;
        }

        private float screenY(float mapY, RectF rect) {
            float imageY = pointScreenY(mapY, rect);
            return getHeight() / 2.0f + zoom * (imageY - getHeight() / 2.0f) + panY;
        }

        private void confirmWorldMarker() {
            if (mapBitmap == null || currentMapSpaceId == 0) return;
            RectF rect = fittedMapRect();
            float x = (getWidth()/2f - panX/zoom - rect.left) / rect.width();
            float y = (getHeight()/2f - panY/zoom - rect.top) / rect.height();
            long space = currentMapSpaceId;
            confirmCustomMarker(String.format(Locale.ROOT, "PLACEWORLD %d %.7f %.7f", space, x, y),
                    x, y, () -> mapCanvas == this && !localMapSelected && space == currentMapSpaceId);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            taps.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    lastTouchX = downTouchX = event.getX();
                    lastTouchY = downTouchY = event.getY();
                    dragging = false;
                    longPressHandled=false;
                    postDelayed(longPressAction,android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    removeCallbacks(longPressAction);
                    dragging=true;
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    int remaining = event.getActionIndex() == 0 ? 1 : 0;
                    lastTouchX = event.getX(remaining);
                    lastTouchY = event.getY(remaining);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (scaleDetector.isInProgress() || event.getPointerCount() > 1) return true;
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    if (Math.abs(event.getX() - downTouchX) + Math.abs(event.getY() - downTouchY)
                            > dp(7)) dragging = true;
                    if (dragging) {
                        removeCallbacks(longPressAction);
                        panX += dx;
                        panY += dy;
                        invalidate();
                    }
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    removeCallbacks(longPressAction);

                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    removeCallbacks(longPressAction);
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private static final class MapMarkerEntry {
        final int type;
        final boolean canTravel;
        final float x;
        final float y;
        final long refId;
        final String name;

        MapMarkerEntry(int type, boolean canTravel, float x, float y, long refId, String name) {
            this.type = type;
            this.canTravel = canTravel;
            this.x = x;
            this.y = y;
            this.refId = refId;
            this.name = name;
        }
    }

    private static final class RadioStation {
        final long refId;
        final String name;

        RadioStation(long refId, String name) {
            this.refId = refId;
            this.name = name;
        }
    }

    private static boolean sameInventory(List<InventoryEntry> left, List<InventoryEntry> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); ++i) {
            InventoryEntry a = left.get(i), b = right.get(i);
            if (!a.category.equals(b.category) || a.count != b.count || a.formId != b.formId
                    || a.equipped != b.equipped || a.usable != b.usable || a.ammoApplicable != b.ammoApplicable
                    || !a.group.equals(b.group) || a.instanceToken != b.instanceToken
                    || a.condition != b.condition || a.modFlags != b.modFlags || a.hotkey != b.hotkey
                    || a.weight != b.weight || a.value != b.value || a.canDrop != b.canDrop
                    || a.medicine != b.medicine || !a.foodRelief.equals(b.foodRelief)
                    || !a.waterRelief.equals(b.waterRelief) || !a.sleepRelief.equals(b.sleepRelief)
                    || !a.name.equals(b.name)) return false;
        }
        return true;
    }

    private static final class InventoryEntry {
        final String category;
        final int count;
        final long formId;
        final boolean equipped;
        final boolean usable;
        final boolean ammoApplicable;
        double weight, value;
        String foodRelief = "", waterRelief = "", sleepRelief = "";
        boolean canDrop;
        int medicine;
        final String group;
        final long instanceToken;
        final int condition;
        final int modFlags;
        final int hotkey;
        final String name;

        InventoryEntry(String category, int count, long formId, boolean equipped,
                       boolean usable, boolean ammoApplicable, String group, long instanceToken,
                       int condition, int modFlags, int hotkey, String name) {
            this.category = category;
            this.count = count;
            this.formId = formId;
            this.equipped = equipped;
            this.usable = usable;
            this.ammoApplicable = ammoApplicable;
            this.group = group;
            this.instanceToken = instanceToken;
            this.condition = condition;
            this.modFlags = modFlags;
            this.hotkey = hotkey;
            this.name = name;
        }
    }

    private static final class QuestDestination {
        final float x;
        final float y;
        final List<PointF> route = new ArrayList<>();

        QuestDestination(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class QuestEntry {
        final String quest;
        final String objective;
        final List<String> optionalObjectives;
        final long questFormId;
        final boolean tracked;

        QuestEntry(long questFormId, boolean tracked, String quest, String objective,
                   String[] optionalObjectives) {
            this.quest = quest;
            this.objective = objective;
            this.optionalObjectives = new ArrayList<>();
            for (String optionalObjective : optionalObjectives) {
                String trimmed = optionalObjective.trim();
                if (!trimmed.isEmpty()) this.optionalObjectives.add(trimmed);
            }
            this.questFormId = questFormId;
            this.tracked = tracked;
        }
    }

    private static final class ChallengeEntry {
        final long formId;
        final String name;
        final int progress, threshold;
        final boolean completed, recurring;

        ChallengeEntry(long formId, String name, int progress, int threshold,
                       boolean completed, boolean recurring) {
            this.formId = formId;
            this.name = name;
            this.progress = progress;
            this.threshold = threshold;
            this.completed = completed;
            this.recurring = recurring;
        }
    }

    private static final class PerkEntry {
        final long formId;
        final String name;
        final int rank;

        PerkEntry(long formId, String name, int rank) {
            this.formId = formId;
            this.name = name;
            this.rank = rank;
        }
    }

    private static final class ReputationEntry {
        final String name;
        final int titleCode;

        ReputationEntry(String name, int titleCode) {
            this.name = name;
            this.titleCode = titleCode;
        }
    }

    private static final class NoteEntry {
        final long formId;
        boolean read;
        final int type;
        final String name;
        final String content;

        NoteEntry(long formId, boolean read, int type, String name, String content) {
            this.formId = formId;
            this.read = read;
            this.type = type;
            this.name = name;
            this.content = content;
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (contentDialog != null) { contentDialog.dismiss(); contentDialog = null; }
        if (contentPopup != null) { contentPopup.dismiss(); contentPopup = null; }
        dismissVersionMismatch();
        if (waitingOverlay != null) waitingOverlay.animate().cancel();
        bridgeCommandExecutor.shutdownNow();
        closeQuietly(activeSocket);
        closeQuietly(serverSocket);
        super.onDestroy();
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
