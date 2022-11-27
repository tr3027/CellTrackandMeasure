import java.awt.*;
import java.awt.event.*;
// import java.util.*;
import ij.*;
import ij.io.*;
import ij.Menus.*; // used to check if CellMagicWandTool available
import ij.process.*;
import ij.gui.*;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.frame.*;
// import java.awt.datatransfer.*;
import cellMagicWand.*;
import java.time.format.DateTimeFormatter;  
import java.time.LocalDateTime;    


public class Cell_Track_and_Measure extends PlugInFrame implements ImageListener, ActionListener, Runnable, MouseListener, PlugIn, ItemListener/*,
ClipboardOwner, KeyListener, */ {

    private static final String TITLE = "Cell Track and Measure";
    private static final String BTN_CMWT_CONFIG = "MagicWand config";
    private static final String BTN_CTAM_CONFIG = "Tracking config";
    private static final String BTN_OVERLAY = "Track & overlay";
    private static final String BTN_MEASURE = "Track & measure";
    private static final String LBL_CHANNEL = "Select channel to track in:";
    private static final String CHK_ROI_ON_CLICK = "Add ROI on click";
    private static final String CHK_PLOT_DATA = "Plot data";
    private static final String CHK_AUTO_SAVE = "Auto save CSV";
    private static final String ERR_NO_CMWT = "Dependant plugin 'Cell_Magic_Wand_Tool' not installed! Please go get it from https://github.com/fitzlab/CellMagicWand";
    private static final String FMT_HEADING_CELL = "Cell %d: %s";
    private static final String FMT_HEADING_CELL_AREA = "Cell %d: area";
    private static final String FMT_HEADING_CELL_DIFF = "Cell %d: area diff";
    private static final String FMT_HEADING_AVG = "Average: %s";
    private static final String FMT_HEADING_ERR = "Error: %s";
    private static final String FMT_PLOT_CELL = "Cell %d";
    private static final String FMT_PLOT_AVG = "Average";

    java.awt.Color[] plotColors = {Color.red, Color.green, Color.blue, Color.black, Color.yellow, Color.orange, Color.pink, Color.gray};

    private static final double DEFAULT_CELL_AREA_DIFF = 0.1;
    private static final int DEFAULT_CELL_CENTER_DIST = 2;
    private static final boolean DEFAULT_CHK_ROI_ON_CLICK = true;
    private static final boolean DEFAULT_CHK_PLOT_DATA = true;
    private static final boolean DEFAULT_CHK_AUTO_SAVE = true;


    static Frame instance; // instance of this plugin
    private Thread thread; // plugin thread instance
    RoiManager roiManager; // ROI manager instance
    Cell_Magic_Wand_Tool cmwt; // Cell Magic Wand Tool instance
    String fileName, filePath; // holds the file info of the current image


    Panel panel; // plugin control panel
    boolean bRoiOnClick, bPlotData, bAutoSave; // used temporarily to load saved state of checkboxes
    String trackingChannel; // used to hold last used tracking channel (to reselect it aoutomatically in every image if it exists)
    java.awt.Checkbox roiOnClick, plotData, autoSave;
    java.awt.Choice channelChoice = new Choice();
    java.awt.Button btnOverlay, btnMeasure;

    ImagePlus currentImp = null;

    boolean done = false;
    private ImageCanvas previousCanvas = null;

    /* maximum acceptable area difference of a ROI between two frames (1=100%) */
    double cellAreaDiff = 0.1;

    /* maximum distance from previous frame's ROI center to search for a cell in pixels.
       The algorithm makes maximum this number of clock-wise turns around the original center. */
    int cellCenterDist = 2;

    public Cell_Track_and_Measure() {
        super(Cell_Track_and_Measure.TITLE); // set plugin window title

        try { // try to instanciate the CMWT and the rest of the plugin
            cmwt = new Cell_Magic_Wand_Tool();

            if (instance != null) {
                instance.toFront();
            } else {
                loadPrefs();
                instance = this;
                ImagePlus.addImageListener(this);
                WindowManager.addWindow(this);
                setLayout(new FlowLayout(FlowLayout.CENTER,5,5));

                panel = new Panel();
                panel.setLayout(new GridLayout(12, 0, 0, 0));
            
                panel.add(myButton(Cell_Track_and_Measure.BTN_CMWT_CONFIG, true));
                panel.add(myButton(Cell_Track_and_Measure.BTN_CTAM_CONFIG, true));

                panel.add(new Label()); // just a spacer
                
                // still empty Choice component -> will get options added by the run() method
                panel.add(new Label(Cell_Track_and_Measure.LBL_CHANNEL));
                channelChoice.setEnabled(false);
                channelChoice.addItemListener(this);
                panel.add(channelChoice);

                panel.add(new Label()); // just a spacer
                
                panel.add(btnOverlay = myButton(Cell_Track_and_Measure.BTN_OVERLAY, false));
                panel.add(btnMeasure = myButton(Cell_Track_and_Measure.BTN_MEASURE, false));

                panel.add(new Label()); // just a spacer
                
                roiOnClick = new Checkbox(Cell_Track_and_Measure.CHK_ROI_ON_CLICK, bRoiOnClick);
                roiOnClick.addItemListener(this);
                panel.add(roiOnClick);
                
                plotData = new Checkbox(Cell_Track_and_Measure.CHK_PLOT_DATA, bPlotData);
                plotData.addItemListener(this);
                panel.add(plotData);

                autoSave = new Checkbox(Cell_Track_and_Measure.CHK_AUTO_SAVE, bAutoSave);
                autoSave.addItemListener(this);
                panel.add(autoSave);

                add(panel);
                pack();
                this.setVisible(true);

                // run the plugin
                thread = new Thread(this,"ctam");
                thread.setPriority(Math.max(thread.getPriority()-2,thread.MIN_PRIORITY));
                thread.start();
            }
        } catch (NoClassDefFoundError e) { // bail out if CMWT is not installed
            IJ.showMessage(Cell_Track_and_Measure.ERR_NO_CMWT);
            throw new RuntimeException(Macro.MACRO_CANCELED);
        }
    } // Cell_Track_and_Measure.Cell_Track_and_Measure()

	public void showPrefsDialog() {
        GenericDialog gd = new GenericDialog(Cell_Track_and_Measure.TITLE + " config");

        gd.addMessage("Maximum cell differences between frames");
		gd.addNumericField("Area: ", cellAreaDiff, 1);
        gd.addMessage("");
        gd.addNumericField("ROI search center distance", cellCenterDist);

        gd.setResizable(false);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        cellAreaDiff = (double)gd.getNextNumber();
        cellCenterDist = (int)gd.getNextNumber();
        savePrefs();
	} // Cell_Track_and_Measure.showPrefsDialog()


    Button myButton(String label, boolean enabled) {
		Button btn = new Button(label);
        btn.setEnabled(enabled);
		btn.addActionListener(this);
		return btn;
	} // Cell_Track_and_Measure.myButton()

    public void imageOpened(ImagePlus imp) {
    } // Cell_Track_and_Measure.imageOpened()

    public void imageUpdated(ImagePlus imp) {
    } // Cell_Track_and_Measure.imageUpdated()

    public void imageClosed(ImagePlus imp) {
        //imp.getCanvas().removeMouseListener(this);
        // imp.getCanvas().removeKeyListener(this);
    } // Cell_Track_and_Measure.imageClosed()

    public void actionPerformed(ActionEvent e) {
		String command = e.getActionCommand();
		if (command != null) {
            switch (command) {
                case Cell_Track_and_Measure.BTN_CMWT_CONFIG:
                    cmwt.showOptionsDialog();
                    break;
                case Cell_Track_and_Measure.BTN_CTAM_CONFIG:
                    showPrefsDialog();
                    break;
                case Cell_Track_and_Measure.BTN_OVERLAY:
                    trackCells(true);
                    // vytvorit overlay
                    break;
                case Cell_Track_and_Measure.BTN_MEASURE:
                    trackCells(false);
                    break;
            }
        }
	} // Cell_Track_and_Measure.actionPerformed()

    @Override
    public void run() {
        roiManager = RoiManager.getInstance();
        roiManager = roiManager == null ? new RoiManager() : roiManager;

        while (!done) {
            // wait 0.5s to not overload the CPU
            try { Thread.sleep(500); } catch(InterruptedException e) {}
            
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp != currentImp) {    // only if another image selected
                if (imp != null) {      // if there is an image
                    FileInfo fInfo = imp.getOriginalFileInfo();
                    try {
                        if (!fInfo.fileName.isEmpty()) { // only if current image was loaded from a file (fails for our plots) 
                            fileName = fInfo.fileName;
                            filePath = fInfo.directory;
                            // roiManager.reset(); // clear ROIs if switched to another image
                            roiManager.runCommand("show all"); // ensure all ROIs will be shown
                            channelChoice.removeAll();
                            for (int i = 1; i<imp.getNChannels()+1; i++) {
                                String channelName = imp.getProp("[Channel "+i+" Parameters] DyeName");
                                if (channelName != null) channelChoice.add(channelName.isEmpty() ? "Channel "+i : channelName);
                            }
                        }
                    } catch (Exception e) {}
                    if (channelChoice.getItemCount() > 0 && !trackingChannel.isEmpty()) {
                        channelChoice.select(trackingChannel);
                        imp.setC(channelChoice.getSelectedIndex()+1);
                    }
                    ImageCanvas canvas = imp.getCanvas();
                    if (canvas != previousCanvas) {
                        if (previousCanvas != null)
                            previousCanvas.removeMouseListener(this);
                        canvas.addMouseListener(this);
                        previousCanvas = canvas;
                    }
                } else {
                    if (previousCanvas != null) {
                        previousCanvas.removeMouseListener(this);
                    }
                    previousCanvas = null;
                }

                boolean enableControls = channelChoice.getItemCount() > 0 && imp!=null;
                channelChoice.setEnabled(enableControls);
                btnOverlay.setEnabled(enableControls);
                btnMeasure.setEnabled(enableControls);

                currentImp = imp;
            }
		}
	} // Cell_Track_and_Measure.run()

    /** computes absulute percentual differene between two values */
    private double percDiff(double oldValue, double newValue) {
        return Math.abs((newValue - oldValue) / (oldValue));
    } // Cell_Track_and_Measure.percDiff()

    /** Tracks the selected cells within the selected channel 
        and creates an overlay or measures the cells based 
        on the parameter <code>overlay</code>.
        */
    public void trackCells(boolean overlay) {
        Roi[] rois = roiManager.getSelectedRoisAsArray(); // list of created ROIs

        if (rois.length > 0) { // only proceed if we have at least 1 ROI
            ImagePlus imp = WindowManager.getCurrentImage();
            ImageStatistics stat = new ImageStatistics();
            ij.measure.Calibration calib = imp.getCalibration();
            int framesCount = imp.getNFrames(); 
            int channelCount = channelChoice.getItemCount();
            ResultsTable res = new ResultsTable();
            Overlay ovr = new Overlay();
            double[] sum, sqSum, roiArea;
            double[] firstCellArea = new double[rois.length]; // area of the cell on the 1st frame, used to compute resize ratio on each frame
            int[] roiX, roiY;

            IJ.showStatus("Tracking "+rois.length+" cell"+(rois.length > 1 ? "s" : "")+" in "+framesCount+" frames ...");

            // cycle through all the frames available except the last one as this one can be incomplete (partially scanned, containing blue-filled area), which kicks CMWT into exception
            for (int frameIndex = 0; frameIndex < framesCount-1; frameIndex++) {
                IJ.showProgress(frameIndex+1, framesCount);

                // setPosition requires 1-based indices !
                /* @TODO: maybe some day support for more then 1 slice could be added... */
                imp.setPosition(channelChoice.getSelectedIndex()+1, 1, frameIndex+1); 

                sum = new double[channelCount]; // sum of all ROI means within a frame for each channel
                sqSum = new double[channelCount]; // Square sum of all ROI means within a frame for each channel
                
                // ROI data for each ROI in a single frame of the tracking channel
                roiX = new int[rois.length]; 
                roiY = new int[rois.length];
                roiArea = new double[rois.length];

                if (!overlay) res.incrementCounter();

                for (var roiIndex = 0; roiIndex < rois.length; roiIndex++) { //for each ROI in the frame
                    int x=0,y=0,row=0; // used to circle around the original ROI's center if needed
                    int bestX=0,bestY=0; // used to store best ROI match's coordinates
                    double bestAreaDiff; // used to store best ROI match's area percentage difference to previous frame's ROI area
                    Roi tmpRoi = rois[roiIndex]; // temporary ROI used to detect a cell -> originally initated to previous frame's ROI
                    imp.setRoi(tmpRoi);

                    // get current ROIs statistics in the tracking channel
                    stat = imp.getStatistics(ij.measure.Measurements.MEAN + ij.measure.Measurements.AREA + ij.measure.Measurements.CENTROID);
                    roiX[roiIndex] = (int) Math.round(stat.xCentroid / calib.pixelWidth);
                    roiY[roiIndex] = (int) Math.round(stat.yCentroid / calib.pixelHeight);
                    roiArea[roiIndex] = stat.area;

                    if (frameIndex > 0) { // on all but 1st frame regenerate the ROI from the center of the ROI. In the first frame user created the ROI already!
                        x=y=row=0; // used to circle around the original ROI's center if needed
                        bestAreaDiff = 999; // needs to initialize with high value befor entering the cell search loop !
                        // while our ROI is not aproximately as huge and as bright as the previous ROI generate another ROI with it's center around 
                        // previous ROI's center up to a defined max distance
                        do {
                            // generate new ROI in the centre of the previous frame's ROI
                            tmpRoi = cmwt.makeRoi(roiX[roiIndex] + x, roiY[roiIndex] + y, imp);

                            imp.setRoi(tmpRoi);
                            stat = imp.getStatistics(ij.measure.Measurements.MEAN + ij.measure.Measurements.AREA + ij.measure.Measurements.CENTROID);
                            int tmpX = (int) Math.round(stat.xCentroid / calib.pixelWidth);
                            int tmpY = (int) Math.round(stat.yCentroid / calib.pixelHeight);

                            double pDiff = percDiff(roiArea[roiIndex], stat.area);
                            // are we in the limits for ROI difference between frames? => found our current frame ROI!
                            if (
                                (pDiff < cellAreaDiff) &&  // area difference smaller then default 10%
                                (tmpX >= roiX[roiIndex] - cellCenterDist) &&                 // new ROI's center within default 2px distance of original ROI
                                (tmpX <= roiX[roiIndex] + cellCenterDist) && 
                                (tmpY >= roiY[roiIndex] - cellCenterDist) && 
                                (tmpY <= roiY[roiIndex] + cellCenterDist)
                            ) {
                                break;
                            }

                            // if this cell is a better match then the previous best match, then remember it's percDiff and click-position
                            if (pDiff < bestAreaDiff) {
                                bestAreaDiff = pDiff;
                                bestX = roiX[roiIndex] + x;
                                bestY = roiY[roiIndex] + y;
                            }

                            // circle arround the original ROI's center clock-wise to find a better ROI on current frame
                            if (row == 0 || (x == -1 && y == -row)) {
                                row++;
                                if (row < (cellCenterDist+1)) {
                                    x=0;
                                    y=-row;
                                }
                            } else if (y == -row && x < row) {
                                x++;
                            } else if (x == row && y < row) {
                                y++;
                            } else if (y == row && x > -row) {
                                x--;
                            } else if (x == -row && y > -row) {
                                y--;
                            }

                        } while (row < (cellCenterDist+1));
                        if (row == (cellCenterDist+1)) {
                            // we'e not found a perfect match => use the best possible match
                            tmpRoi = cmwt.makeRoi(bestX, bestY, imp);
                            IJ.log("Cell "+(roiIndex+1)+" not detected correctly in frame "+(frameIndex+1)+". Check the overlay!");
                        }
                        rois[roiIndex] = tmpRoi; // write the regenerated ROI back to our ROI array
                    } else if (frameIndex == 0) {
                        firstCellArea[roiIndex] = stat.area; // remember 1st frame ROI area
                    }

                    // apply the ROI to all channels
                    for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                        if (overlay) {
                            tmpRoi = (PolygonRoi)tmpRoi.clone();
                            tmpRoi.setPosition(channelIndex+1, 1, frameIndex+1);
                            ovr.add(tmpRoi);
                            PointRoi tmpPoint = new PointRoi(roiX[roiIndex] + x, roiY[roiIndex] + y);
                            tmpPoint.setPosition(channelIndex+1, 1, frameIndex+1);
                            ovr.add(tmpPoint);
                        } else {
                            imp.setPosition(channelIndex+1, 1, frameIndex+1);
                            imp.setRoi(tmpRoi);
                            stat = imp.getStatistics(ij.measure.Measurements.MEAN + ij.measure.Measurements.AREA);
                            res.addValue(String.format(FMT_HEADING_CELL,roiIndex+1, channelChoice.getItem(channelIndex)), stat.mean);
                            // res.addValue("Cell "+(roiIndex+1)+": "+channelChoice.getItem(channelIndex), stat.mean);
                            sum[channelIndex] += stat.mean;
                            sqSum[channelIndex] += (stat.mean * stat.mean);
                        }
                    }

                    if (!overlay) {
                        res.addValue(String.format(FMT_HEADING_CELL_AREA, roiIndex+1), stat.area);
                        res.addValue(String.format(FMT_HEADING_CELL_DIFF, roiIndex+1), stat.area / firstCellArea[roiIndex]);
                        // res.addValue("Cell "+(roiIndex+1)+": area", stat.area);
                        // res.addValue("Cell "+(roiIndex+1)+": area diff", stat.area / firstCellArea[roiIndex]);
                    }
                }

                if (!overlay) { // compute average/error per channel
                    for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                        double average = sum[channelIndex] / rois.length;
                        double variance = ((sqSum[channelIndex] / rois.length) - (average * average));
                        double error = java.lang.Math.sqrt(variance / rois.length);
                        res.addValue(String.format(FMT_HEADING_AVG, channelChoice.getItem(channelIndex)), average);
                        res.addValue(String.format(FMT_HEADING_ERR, channelChoice.getItem(channelIndex)), error);
                        // res.addValue("Average: "+channelChoice.getItem(channelIndex), average);
                        // res.addValue("Error: "+channelChoice.getItem(channelIndex), error);
                    }
                }
            }

            if (overlay) {
                ovr.setStrokeColor(Color.red);
                imp.setOverlay(ovr); // add the Overlay to the image
            } else {
                res.show("Trace data");
                if (plotData.getState()) { // a graph was requested as wellb
                    for (var channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                        String[] legends = new String[rois.length+1];
                        Plot plot = new Plot(channelChoice.getItem(channelIndex), "time", "mean");
                        for (var roiIndex = 0; roiIndex < rois.length; roiIndex++) {
                            plot.setColor(plotColors[roiIndex]);
                            legends[roiIndex] = String.format(FMT_PLOT_CELL, roiIndex+1);
                            plot.add("line", res.getColumn(String.format(FMT_HEADING_CELL, roiIndex+1, channelChoice.getItem(channelIndex))));
                        }
                        plot.setColor(plotColors[rois.length]);
                        legends[rois.length] = String.format(FMT_PLOT_AVG);
                        plot.add("line", res.getColumn(String.format(FMT_HEADING_AVG, channelChoice.getItem(channelIndex))));
                        plot.setLimitsToFit(true);
                        plot.addLegend(String.join("\t", legends));
                        plot.draw();
                        plot.show();
                    }
                }
                if (autoSave.getState()) { // export data to CSV if the user enabled this option
                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyMMdd_HHmmss");  
                    // "/path/to/the/" "file" _ "yymmdd_hhmmss" .csv
                    String fn = String.format("%s%s_%s.csv", filePath, fileName.split("\\.(?=[^\\.]+$)")[0], dtf.format(LocalDateTime.now()));
                    if (!res.save(fn)) {
                        IJ.showMessage("Failed to save measurements to file: "+ fn);
                    }
                }
            }
        } else {
            IJ.showMessage("You need to add at least 1 ROI first!");
        }
    } // Cell_Track_and_Measure.createOverlay()

    public void mouseExited(MouseEvent e) {}
    public void mouseEntered(MouseEvent e) {}
    public void mouseReleased(MouseEvent e) {}
    public void mousePressed(MouseEvent e) {}

    public void mouseClicked(MouseEvent e) {
        if (roiOnClick.getState()) { // if "ROI on click" enabled send the click through to CMWT
            ImagePlus imp = WindowManager.getCurrentImage();
            cmwt.mousePressed(imp, e);
        }
    } // Cell_Track_and_Measure.mouseClicked()

    public void itemStateChanged(ItemEvent e) {
        // if tracking channel changed, switch the current image to that channel
        if (e.getSource() == channelChoice) {
            ImagePlus imp = WindowManager.getCurrentImage();
            imp.setC(channelChoice.getSelectedIndex()+1);
            trackingChannel = channelChoice.getSelectedItem();
        }

        // save preferences each time any item (Checkbox/Choice) changed
        savePrefs();
    } // Cell_Track_and_Measure.itemStateChanged()

    public void windowClosed(WindowEvent e) {
        instance = null;
        done = true;
        ImagePlus.removeImageListener(this);
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp != null){
            ImageCanvas canvas = imp.getCanvas();
            if(canvas != null){
                canvas.removeMouseListener(this);
            }
            if(previousCanvas != null)
                previousCanvas.removeMouseListener(this);
        }
        previousCanvas = null;
        super.windowClosed(e);
    } // Cell_Track_and_Measure.windowClosed()

    private void loadPrefs() {
        cellAreaDiff = Prefs.getDouble("CellTrackandMeasure.cellAreaDiff", Cell_Track_and_Measure.DEFAULT_CELL_AREA_DIFF);
        cellCenterDist = Prefs.getInt("CellTrackandMeasure.cellCenterDist", Cell_Track_and_Measure.DEFAULT_CELL_CENTER_DIST);
        bPlotData = Prefs.get("CellTrackandMeasure.chkPlotData", Cell_Track_and_Measure.DEFAULT_CHK_PLOT_DATA);
        bRoiOnClick = Prefs.get("CellTrackandMeasure.chkRoiOnClick", Cell_Track_and_Measure.DEFAULT_CHK_ROI_ON_CLICK);
        bAutoSave = Prefs.get("CellTrackandMeasure.chkAutoSave", Cell_Track_and_Measure.DEFAULT_CHK_AUTO_SAVE);
        String track = Prefs.get("CellTrackandMeasure.trackingChannel", "");
        if (!track.isEmpty()) trackingChannel = track;
    } // Cell_Track_and_Measure.loadPrefs()

    private void savePrefs() {
        Prefs.set("CellTrackandMeasure.cellAreaDiff", cellAreaDiff);
        Prefs.set("CellTrackandMeasure.cellCenterDist", cellCenterDist);
        Prefs.set("CellTrackandMeasure.chkPlotData", plotData.getState());
        Prefs.set("CellTrackandMeasure.chkRoiOnClick", roiOnClick.getState());
        Prefs.set("CellTrackandMeasure.chkAutoSave", autoSave.getState());
        if (!trackingChannel.isEmpty()) Prefs.set("CellTrackandMeasure.trackingChannel", trackingChannel);
        Prefs.savePreferences();
    } // Cell_Track_and_Measure.savePrefs()

} // class Cell_Track_and_Measure