/*
 * MainController.java - Copyright(c) 2013 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Jul 22, 2013
 */

package org.noroomattheinn.visibletesla;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Dialogs;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.commons.lang3.StringUtils;
import org.noroomattheinn.tesla.ActionController;
import org.noroomattheinn.tesla.GUIState;
import org.noroomattheinn.tesla.Options;
import org.noroomattheinn.tesla.Result;
import org.noroomattheinn.tesla.Tesla;
import org.noroomattheinn.tesla.Vehicle;
import org.noroomattheinn.tesla.VehicleState;
import org.noroomattheinn.utils.Utils;
import org.noroomattheinn.utils.Versions;
import org.noroomattheinn.utils.Versions.Release;
import org.noroomattheinn.visibletesla.AppContext.InactivityMode;

/**
 * This is the main application code for VisibleTesla. It does not contain
 * the main function. Main is in VisibleTesla.java which is mostly just a shell.
 * This controller is associated with the Tab panel in which all of the 
 * individual tabs live.
 * 
 * TO DO:
 * - If the user has more than one vehicle on their account, the app should pop
 *   up a list and let them  select a specific car. Perhaps represent them by 
 *   vin and small colored car icon.
 *
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
public class MainController extends BaseController {
    
/*------------------------------------------------------------------------------
 *
 * Constants and Enums
 * 
 *----------------------------------------------------------------------------*/

    private static final long IdleThreshold = 15 * 60 * 1000;   // 15 Minutes
    private static final int MaxTriesToStart = 10;
    private static final String VersionsFile = 
        "https://dl.dropboxusercontent.com/u/7045813/VisibleTesla/versions.xml";
/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/

    private Tesla tesla;
    private Vehicle selectedVehicle;
    private InactivityMode inactivityMode;
    private boolean initialSetup = false;
    
/*------------------------------------------------------------------------------
 *
 * UI Elements
 * 
 *----------------------------------------------------------------------------*/

    // The top level AnchorPane and the TabPane that sits inside it
    @FXML private TabPane tabPane;

    // The individual tabs that comprise the overall UI
    @FXML private Tab prefsTab;
    @FXML private Tab schedulerTab;
    @FXML private Tab graphTab;
    @FXML private Tab chargeTab;
    @FXML private Tab hvacTab;
    @FXML private Tab locationTab;
    @FXML private Tab loginTab;
    @FXML private Tab overviewTab;
    
    // The menu items that are handled in this controller directly
    @FXML private RadioMenuItem allowSleepMenuItem;
    @FXML private RadioMenuItem allowIdlingMenuItem;
    @FXML private RadioMenuItem stayAwakeMenuItem;
    
/*==============================================================================
 * -------                                                               -------
 * -------              Public Interface To This Class                   ------- 
 * -------                                                               -------
 *============================================================================*/

    /**
     * Called by the main application to allow us to store away the app context
     * and perform any other app startup tasks. In particular, we (1) distribute
     * app context to all of the controllers, and (2) we set a listener for login
     * completion and try and automatic login.
     * @param a 
     */
    public void start(Application a, Stage s) {
        appContext = new AppContext(a, s);
        inactivityMode = readInactivityMenu();

        setTitle();
        appContext.stage.getIcons().add(new Image(getClass().getClassLoader().getResourceAsStream(
                "org/noroomattheinn/TeslaResources/Icon-72@2x.png")));

        tesla = new Tesla();
        tesla.setCookieDir(appContext.appFilesFolder);

        tabPane.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<Tab>() {
            @Override public void changed(ObservableValue<? extends Tab> ov, Tab t, Tab t1) {
                BaseController c = controllerFromTab(t1);
                if (c != null) {
                    c.activate(selectedVehicle);
                }
            }
        });

        controllerFromTab(loginTab).setAppContext(appContext);
        controllerFromTab(prefsTab).setAppContext(appContext);
        controllerFromTab(schedulerTab).setAppContext(appContext);
        controllerFromTab(graphTab).setAppContext(appContext);
        controllerFromTab(chargeTab).setAppContext(appContext);
        controllerFromTab(hvacTab).setAppContext(appContext);
        controllerFromTab(locationTab).setAppContext(appContext);
        controllerFromTab(overviewTab).setAppContext(appContext);
        
        LoginController lc = Utils.cast(controllerFromTab(loginTab));
        lc.getLoginCompleteProperty().addListener(new HandleLoginEvent());
        lc.attemptAutoLogin(tesla);
        
        appContext.inactivityState.addListener(new ChangeListener<InactivityMode>() {
            @Override public void changed(
                    ObservableValue<? extends InactivityMode> o,
                    InactivityMode ov, InactivityMode nv) { setTitle(); }
        });
        appContext.setInactivityModeListener(new Utils.Callback<InactivityMode,Void>() {
            @Override public Void call(InactivityMode mode) {
                setInactivityMode(mode);
                return null; }});
    }
    
    public void stop() {
        appContext.shutDown();
        SchedulerController sc = Utils.cast(controllerFromTab(schedulerTab));
        sc.shutDown();
    }
    
/*------------------------------------------------------------------------------
 *
 * Methods overridden from BaseController. We implement BaseController so that
 * we can perform issueCommand operations.
 * 
 *----------------------------------------------------------------------------*/
    
    @Override protected void fxInitialize() { }

    @Override protected void prepForVehicle(Vehicle v) { }

    @Override protected void refresh() { }

    /**
     * This is effectively the last half of the DoLogin process. We have to
     * break it up because some bits run on the JavaFX UI thread and some have
     * to run in the background.
     */
    @Override protected void reflectNewState() {
        if (!initialSetup) return;
        initialSetup = false;
        
        if (appContext.cachedGUIState == null ||
            appContext.cachedVehicleState == null ||
            !appContext.cachedGUIState.hasValidData() ||
            !appContext.cachedVehicleState.hasValidData()) {
            // Couldn't wake up the vehicle and get the gui and vehicle state!
            Dialogs.showErrorDialog(appContext.stage,
                    "Failed to connect to your vehicle even after a successful " +
                    "login. It may be in a deep sleep and can't be woken up.\n"  +
                    "\nPlease try to wake your Tesla and then try VisibleTesla again.",
                    "Unable to communicate with your Tesla", "Communication Problem");
            Tesla.logger.log(Level.SEVERE, "Can't communicate with vehicle - exiting.");
            Platform.exit();
            return;
        }
        
        trackInactivity();
        setTabsEnabled(true);
        SchedulerController sc = Utils.cast(controllerFromTab(schedulerTab));
        sc.activate(selectedVehicle);
        jumpToTab(overviewTab);
}

    
/*------------------------------------------------------------------------------
 *
 * Dealing with a Login Event
 * 
 *----------------------------------------------------------------------------*/
    
    /**
     * Whenever we try to login, a boolean property is set with the result of
     * the attempt. We monitor changes on that boolean property and if we see
     * a successful login, we gather the appropriate state and make the app
     * ready to go.
     */
    private class HandleLoginEvent implements ChangeListener<Boolean> {
        @Override public void changed(
                ObservableValue<? extends Boolean> observable,
                Boolean oldValue, Boolean newValue) {
            Platform.runLater(new DoLogin(newValue));
        }
    }

    private Vehicle selectVehicle() {
        int selectedVehicleIndex = 0;
        List<Vehicle> vehicleList = tesla.getVehicles();
        if (vehicleList.size() != 1) {
            // Ask the  user to select a vehicle
            List<String> cars = new ArrayList<>();
            for (Vehicle v : vehicleList) {
                StringBuilder descriptor = new StringBuilder();
                descriptor.append(StringUtils.right(v.getVIN(), 6));
                descriptor.append(": ");
                descriptor.append(v.getOptions().paintColor());
                descriptor.append(" ");
                descriptor.append(v.getOptions().batteryType());
                cars.add(descriptor.toString());
            }
            String selection = Dialogs.showInputDialog(
                    appContext.stage,
                    "Vehicle: ",
                    "You lucky devil, you've got more than 1 Tesla!",
                    "Select a vehicle", cars.get(0), cars);
            selectedVehicleIndex = cars.indexOf(selection);
            if (selectedVehicleIndex == -1) { selectedVehicleIndex = 0; }
        }
        return vehicleList.get(selectedVehicleIndex);
    }

    private boolean cacheBasics(Vehicle v) {
        GUIState     gs = new GUIState(v);
        VehicleState vs = new VehicleState(v);
        ActionController action = new ActionController(v);
        
        int tries = 0;
        gs.refresh();
        vs.refresh();
        while (! (gs.hasValidData() &&  vs.hasValidData()) ) {
            if (tries++ > MaxTriesToStart)
                return false;
            
            action.wakeUp();
            Utils.sleep(500);
            if (appContext.shuttingDown.get()) return false;
            
            if (!gs.hasValidData()) gs.refresh();
            if (!vs.hasValidData()) vs.refresh();
        }
        
        appContext.cachedGUIState = gs;
        appContext.cachedVehicleState = vs;
        return true;
    }

    private class DoLogin implements Runnable {
        private final boolean loginSucceeded;
        DoLogin(boolean loggedin) { loginSucceeded = loggedin; }
        
        @Override
        public void run() {
            if (loginSucceeded) {
                selectedVehicle = selectVehicle();
                Tesla.logger.log(
                        Level.INFO, "Vehicle Info: {0}",
                        selectedVehicle.getUnderlyingValues());
                
                if (selectedVehicle.status().equals("asleep")) {
                    if (letItSleep()) {
                        Tesla.logger.log(
                            Level.INFO, "Allowing vehicle to remain in sleep mode");
                        Platform.exit();
                        return;
                    } else {
                        Tesla.logger.log(Level.INFO, "Waking up your vehicle");
                    }
                }
                
                boolean disclaimer = appContext.prefs.getBoolean(
                        selectedVehicle.getVIN()+"_Disclaimer", false);
                if (!disclaimer) {
                    Dialogs.showInformationDialog(
                            appContext.stage,
                            "Use this application at your own risk. The author\n" +
                            "does not guarantee its proper functioning.\n" +
                            "It is possible that use of this application may cause\n" +
                            "unexpected damage for which nobody but you are\n" +
                            "responsible. Use of this application can change the\n" +
                            "settings on your car and may have negative\n" +
                            "consequences such as (but not limited to):\n" +
                            "unlocking the doors, opening the sun roof, or\n" +
                            "reducing the available charge in the battery.",
                            "Please Read Carefully", "Disclaimer");
                }
                appContext.prefs.putBoolean(
                        selectedVehicle.getVIN()+"_Disclaimer", true);                
                
                conditionalCheckVersion();
                                
                String modeName = appContext.prefs.get(
                        selectedVehicle.getVIN()+"_InactivityMode",
                        InactivityMode.AllowDaydreaming.name());
                inactivityMode = InactivityMode.valueOf(modeName);
                setInactivityMenu(inactivityMode);

                issueCommand(new Callable<Result>() {
                        @Override public Result call() {
                            initialSetup = true;
                            return cacheBasics(selectedVehicle) ?
                                    Result.Succeeded : Result.Failed;
                        } },
                        AfterCommand.Reflect);

            } else {
                selectedVehicle = null;
                setTabsEnabled(false);
            }
        }
        
    }
    
/*------------------------------------------------------------------------------
 *
 * Private Utility Methods - General
 * 
 *----------------------------------------------------------------------------*/

    
    private void setTitle() {
        String title = AppContext.ProductName + " " + AppContext.ProductVersion;
        String time = String.format("%1$tH:%1$tM", new Date());
        switch (appContext.inactivityState.get()) {
            case AllowSleeping: title = title + " [sleeping at " + time + "]"; break;
            case AllowDaydreaming: title = title + " [daydreaming at " + time + "]"; break;
            case StayAwake: break;
        }
        appContext.stage.setTitle(title);
    }
    

    private boolean letItSleep() {
        DialogUtils.DialogController dc = DialogUtils.displayDialog(
                getClass().getResource("WakeSleepDialog.fxml"),
                "Wake up your car?", appContext.stage);
        if (dc == null) return true;
        WakeSleepDialog wsd = Utils.cast(dc);
        return wsd.letItSleep();
    }
    
    private InactivityMode readInactivityMenu() {
        if (allowSleepMenuItem.isSelected()) return InactivityMode.AllowSleeping;
        if (allowIdlingMenuItem.isSelected()) return InactivityMode.AllowDaydreaming;
        return InactivityMode.StayAwake;
    }
    
    private void setInactivityMenu(InactivityMode mode) {
        switch (mode) {
            case StayAwake:
                stayAwakeMenuItem.setSelected(true); break;
            case AllowSleeping:
                allowSleepMenuItem.setSelected(true); break;
            case AllowDaydreaming:
                allowIdlingMenuItem.setSelected(true); break;
        }
    }
    
    private void setInactivityMode(InactivityMode newMode) {
        setInactivityMenu(inactivityMode = newMode);        
        appContext.prefs.put(selectedVehicle.getVIN()+"_InactivityMode", newMode.name());
        
        if (appContext.inactivityState.get() == InactivityMode.StayAwake) return;
        appContext.inactivityState.set(newMode);
    }
    
    private void conditionalCheckVersion() {
        long lastVersionCheck = appContext.prefs.getLong(
                selectedVehicle.getVIN() + "_LastVersionCheck", 0);
        long now = System.currentTimeMillis();
        if (now - lastVersionCheck > (7 * 24 * 60 * 60 * 1000)) {
            checkForNewerVersion();
        }
    }
    
    private boolean checkForNewerVersion() {
        appContext.prefs.putLong(
                selectedVehicle.getVIN() + "_LastVersionCheck", System.currentTimeMillis());
        
        final Versions versions = Versions.getVersionInfo(VersionsFile);
        List<Release> releases = versions.getReleases();

        if (releases != null && !releases.isEmpty()) {
            final Release lastRelease = releases.get(0);
            String releaseNumber = lastRelease.getReleaseNumber();
            if (Utils.compareVersions(AppContext.ProductVersion, releaseNumber) < 0) {
                VBox customPane = new VBox();
                String msgText = String.format(
                        "A newer version of VisibleTesla is available:\n" +
                        "Version: %s, Date: %tD",
                        releaseNumber, lastRelease.getReleaseDate());
                Label msg = new Label(msgText);
                Hyperlink downloadLink = new Hyperlink("Click to download the new release");
                Hyperlink rnLink = new Hyperlink("Click to view the release notes");
                downloadLink.setStyle("-fx-color: blue; -fx-text-fill: blue;");
                downloadLink.setOnAction(new EventHandler<ActionEvent>() {
                    @Override public void handle(ActionEvent t) {
                        appContext.app.getHostServices().showDocument(
                                lastRelease.getReleaseURL().toExternalForm());

                    }
                });
                rnLink.setOnAction(new EventHandler<ActionEvent>() {
                    @Override public void handle(ActionEvent t) {
                        appContext.app.getHostServices().showDocument(
                                versions.getReleaseNotes().toExternalForm());

                    }
                });
                customPane.getChildren().addAll(msg, rnLink, downloadLink);
                Dialogs.showCustomDialog(
                        appContext.stage, customPane,
                        "Newer Version Available",
                        "Checking for Updates", Dialogs.DialogOptions.OK, null);
                return true;
            }
        }
        return false;
    }
    

/*------------------------------------------------------------------------------
 *
 * Private Utility Methods for Tab handling
 * 
 *----------------------------------------------------------------------------*/
    

    private void setTabsEnabled(boolean enabled) {
        prefsTab.setDisable(!enabled);
        schedulerTab.setDisable(!enabled);
        graphTab.setDisable(!enabled);
        chargeTab.setDisable(!enabled);
        hvacTab.setDisable(!enabled);
        locationTab.setDisable(!enabled);
        loginTab.setDisable(false);     // The Login Tab is always enabled
        overviewTab.setDisable(!enabled);
    }
    
    private void jumpToTab(final Tab tab) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                tabPane.getSelectionModel().select(tab);    // Jump to Overview Tab     
            }
        });
    }

    /**
     * Utility method that returns the BaseController object associated with
     * a given tab. It does this by extracting the userData object which each
     * BaseController sets to itself.
     * @param   t   The tab for which we want the BaseController
     * @return      The BaseController
     */
    private BaseController controllerFromTab(Tab t) {
        Object userData = t.getContent().getUserData();
        return (userData instanceof BaseController) ? (BaseController)userData : null;
    }
    
/*------------------------------------------------------------------------------
 *
 * This section implements UI Actionhandlers
 * 
 *----------------------------------------------------------------------------*/
    
    // File->Close
    @FXML void closeHandler(ActionEvent event) {
        Platform.exit();
    }
    
    // File->Export Graph Data...
    @FXML void exportHandler(ActionEvent event) {
        GraphController gc = Utils.cast(controllerFromTab(graphTab));
        gc.exportCSV();
    }
    
    // Options->"Allow Sleep" and Options->"Allow Daydreaming" menu options
    @FXML void inactivityOptionsHandler(ActionEvent event) {
        InactivityMode mode = InactivityMode.StayAwake;
        if (event.getTarget() == allowSleepMenuItem) mode = InactivityMode.AllowSleeping;
        if (event.getTarget() == allowIdlingMenuItem) mode = InactivityMode.AllowDaydreaming;
        setInactivityMode(mode);
    }
    
    // Help->Documentation
    @FXML private void helpHandler(ActionEvent event) {
        appContext.app.getHostServices().showDocument(
                appContext.app.getHostServices().getDocumentBase() +
                "Documentation/Overview.html");
    }
    
    // Help->What's New
    @FXML private void whatsNewHandler(ActionEvent event) {
        appContext.app.getHostServices().showDocument(
                appContext.app.getHostServices().getDocumentBase() +
                "Documentation/ReleaseNotes.html");
    }
    
    // Help->About
    @FXML private void aboutHandler(ActionEvent event) {
        Dialogs.showInformationDialog(
                appContext.stage,
                "Copyright (c) 2013, Joe Pasqua\n" +
                "Free for personal and non-commercial use.\n" +
                "Based on the great API detective work of many members\n" +
                "of teslamotorsclub.com.  All Tesla imagery derives\n" +
                "from the official Tesla iPhone app.",
                AppContext.ProductName + " " + AppContext.ProductVersion,
                "About " + AppContext.ProductName);
    }

    // Help->Check for Updates
    @FXML private void updatesHandler(ActionEvent event) {
        boolean newer = checkForNewerVersion();
        if (!newer) 
            Dialogs.showInformationDialog(
                    appContext.stage,
                    "There is no newer version available.",
                    "Update Check Results", "Checking for Updates");
    }
    
/*------------------------------------------------------------------------------
 * 
 * This section implements the mechanism that tracks whether the app is idle.
 * It does it by hooking the event stream and looking for mouse or keyboard
 * activity. A separate thread checks periodically to see how long it's been
 * since the last event. If a threshold is passed, we say that we're idle.
 * 
 *----------------------------------------------------------------------------*/

    private long timeOfLastEvent = System.currentTimeMillis();

    private void trackInactivity() {
        appContext.stage.addEventFilter(KeyEvent.ANY, new EventPassThrough());
        appContext.stage.addEventFilter(MouseEvent.MOUSE_PRESSED, new EventPassThrough());
        appContext.stage.addEventFilter(MouseEvent.MOUSE_RELEASED, new EventPassThrough());
        appContext.launchThread(new InactivityThread(), "00 Inactivity");
    }
    
    class InactivityThread implements Runnable {
        @Override public void run() {
            while (true) {
                Utils.sleep(60 * 1000);
                if (appContext.shuttingDown.get())
                    return;
                if (System.currentTimeMillis() - timeOfLastEvent > IdleThreshold) {
                    appContext.inactivityState.set(inactivityMode);
                }
            }
        }
    }
    
    class EventPassThrough implements EventHandler<InputEvent> {
        @Override public void handle(InputEvent t) {
            timeOfLastEvent = System.currentTimeMillis();
            appContext.inactivityState.set(InactivityMode.StayAwake);
        }
    }
    

/*------------------------------------------------------------------------------
 * 
 * Everything below has to do with simulated vehicle properties. For example,
 * the user can ask to simulate a different car color, roof type, or wheel
 * type. The simulation options are selected here and implemented in the
 * OverviewController. They could be implemented in the lower level state
 * objects, but I decided to keep those unsullied by this stuff.
 * 
 * TO DO: This way of handling the simulation feels clunky. Perhaps do it
 * with observable properties instead
 * 
 *----------------------------------------------------------------------------*/
    
    @FXML MenuItem simColorRed, simColorBlue, simColorGreen, simColorBrown, simColorBlack;
    @FXML MenuItem simColorSigRed, simColorSilver, simColorGray, simColorPearl, simColorWhite;
    @FXML MenuItem darkRims, lightRims, silver19Rims;
    @FXML MenuItem simSolidRoof, simPanoRoof, simBlackRoof;
    @FXML private MenuItem simImperialUnits, simMetricUnits;

    @FXML void simUnitsHandler(ActionEvent event) {
        MenuItem source = (MenuItem)event.getSource();
        Utils.UnitType ut = (source == simImperialUnits) ? Utils.UnitType.Imperial : Utils.UnitType.Metric;
        appContext.simulatedUnits.set(ut);
    }
    
    @FXML void simRimsHandler(ActionEvent event) {
        MenuItem source = (MenuItem)event.getSource();
        Options.WheelType simWheels = null;

        if (source == darkRims) simWheels = Options.WheelType.WTSP;
        else if (source == lightRims) simWheels = Options.WheelType.WT21;
        else if (source == silver19Rims) simWheels = Options.WheelType.WT19;
        
        if (simWheels != null)
            appContext.simulatedWheels.set(simWheels);
    }
    
    @FXML void simColorHandler(ActionEvent event) {
        MenuItem source = (MenuItem)event.getSource();
        ObjectProperty<Options.PaintColor> pc = appContext.simulatedColor;
        if (source == simColorRed) pc.set(Options.PaintColor.PPMR);
        else if (source == simColorGreen) pc.set(Options.PaintColor.PMSG);
        else if (source == simColorBlue) pc.set(Options.PaintColor.PMMB);
        else if (source == simColorBlack) pc.set(Options.PaintColor.PBSB);
        else if (source == simColorSilver) pc.set(Options.PaintColor.PMSS);
        else if (source == simColorGray) pc.set(Options.PaintColor.PMTG);
        else if (source == simColorSigRed) pc.set(Options.PaintColor.PPSR);
        else if (source == simColorPearl) pc.set(Options.PaintColor.PPSW);
        else if (source == simColorBrown) pc.set(Options.PaintColor.PMAB);
        else if (source == simColorWhite) pc.set(Options.PaintColor.PPSW);
    }

    @FXML void simRoofHandler(ActionEvent event) {
        MenuItem source = (MenuItem)event.getSource();
        ObjectProperty<Options.RoofType> rt = appContext.simulatedRoof;
        if (source == simSolidRoof) rt.set(Options.RoofType.RFBC);
        else if (source == simBlackRoof) rt.set(Options.RoofType.RFBK);
        else if (source == simPanoRoof) rt.set(Options.RoofType.RFPO);
    }

}
