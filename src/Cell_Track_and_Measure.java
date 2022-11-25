import java.awt.*;
import java.awt.event.*;
// import java.util.*;
import ij.*;
import ij.Menus.*; // used to check if CellMagicWandTool available
import ij.process.*;
import ij.gui.*;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.frame.*;
// import java.awt.datatransfer.*;
import cellMagicWand.*;


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
    private static final String ERR_NO_CMWT = "Dependant plugin 'Cell_Magic_Wand_Tool' not installed! Please go get it from https://github.com/fitzlab/CellMagicWand";

    private static final double DEFAULT_CELL_AREA_DIFF = 0.1;
    private static final double DEFAULT_CELL_CENTER_DIST = 0.1;


    static Frame instance; // instance of this plugin
    private Thread thread; // plugin thread instance
    RoiManager roiManager; // ROI manager instance
    Cell_Magic_Wand_Tool cmwt; // Cell Magic Wand Tool instance


    Panel panel; // plugin control panel
    java.awt.Checkbox roiOnClick, plotData;
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
                
                panel.add(roiOnClick = new Checkbox(Cell_Track_and_Measure.CHK_ROI_ON_CLICK, true));
                panel.add(plotData = new Checkbox(Cell_Track_and_Measure.CHK_PLOT_DATA, true));
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
        imp.getCanvas().removeMouseListener(this);
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
                roiManager.reset(); // clear ROIs if switched to another image
                roiManager.runCommand("show all"); // ensure all ROIs will be shown
                if (imp != null) {      // if there is an image
                    channelChoice.removeAll();
                    for (int i = 1; i<imp.getNChannels()+1; i++) {
                        String channelName = imp.getProp("[Channel "+i+" Parameters] DyeName");
                        channelChoice.add(channelName!="" ? channelName : "Channel "+i);
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
                channelChoice.setEnabled(imp!=null);
                btnOverlay.setEnabled(imp!=null);
                btnMeasure.setEnabled(imp!=null);
                currentImp = imp;
            }
		}
	} // Cell_Track_and_Measure.run()

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

            // cycle through all the frames available
            for (int frameIndex = 0; frameIndex < framesCount; frameIndex++) {
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
                            res.addValue("Cell "+(roiIndex+1)+": "+channelChoice.getItem(channelIndex), stat.mean);
                            sum[channelIndex] += stat.mean;
                            sqSum[channelIndex] += (stat.mean * stat.mean);
                        }
                    }

                    if (!overlay) {
                        res.addValue("Cell "+(roiIndex+1)+": area", stat.area);
                        res.addValue("Cell "+(roiIndex+1)+": area diff", stat.area / firstCellArea[roiIndex]);
                    }
                }

                if (!overlay) { // compute average/error per channel
                    for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                        double average = sum[channelIndex] / rois.length;
                        double variance = ((sqSum[channelIndex] / rois.length) - (average * average));
                        double error = java.lang.Math.sqrt(variance / rois.length);
                        res.addValue("Average: "+channelChoice.getItem(channelIndex), average);
                        res.addValue("Error: "+channelChoice.getItem(channelIndex), error);
                    }
                }
            }

            if (overlay) {
                ovr.setStrokeColor(Color.red);
                imp.setOverlay(ovr); // add the Overlay to the image
            } else {
                res.show("Trace data");
                if (plotData.getState()) { // a graph was requested as well
                    /* @TODO */
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
        ImagePlus imp = WindowManager.getCurrentImage();
        imp.setC(channelChoice.getSelectedIndex()+1);
    } // Cell_Track_and_Measure.itemStateChanged()

    private void loadPrefs() {
        cellAreaDiff = (double)Prefs.get("CellTrackandMeasure.cellAreaDiff", Cell_Track_and_Measure.DEFAULT_CELL_AREA_DIFF);
        cellCenterDist = (int)Prefs.get("CellTrackandMeasure.cellCenterDist", Cell_Track_and_Measure.DEFAULT_CELL_CENTER_DIST);
    } // Cell_Track_and_Measure.loadPrefs()

    private void savePrefs() {
        Prefs.set("CellTrackandMeasure.cellAreaDiff", cellAreaDiff);
        Prefs.set("CellTrackandMeasure.cellCenterDist", cellCenterDist);
        Prefs.savePreferences();
    } // Cell_Track_and_Measure.savePrefs()

} // class Cell_Track_and_Measure