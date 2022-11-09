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
    private static final String BTN_OVERLAY = "Track & overlay";
    private static final String BTN_MEASURE = "Track & measure";
    private static final String LBL_CHANNEL = "Select channel to track in:";
    private static final String CHK_ROI_ON_CLICK = "Add ROI on click";
    private static final String CHK_PLOT_DATA = "Plot data";
    private static final String ERR_NO_CMWT = "Dependant plugin 'Cell_Magic_Wand_Tool' not installed! Please go get it from https://github.com/fitzlab/CellMagicWand";


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

    public Cell_Track_and_Measure() {
        super(Cell_Track_and_Measure.TITLE); // set plugin window title

        try { // try to instanciate the CMWT and the rest of the plugin
            cmwt = new Cell_Magic_Wand_Tool();

            if (instance != null) {
                instance.toFront();
            } else {
                instance = this;
                ImagePlus.addImageListener(this);
                WindowManager.addWindow(this);
                setLayout(new FlowLayout(FlowLayout.CENTER,5,5));

                panel = new Panel();
                panel.setLayout(new GridLayout(10, 0, 0, 0));
            
                panel.add(myButton(Cell_Track_and_Measure.BTN_CMWT_CONFIG, true));

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
            int frames = imp.getNFrames(); 
            int channelCount = channelChoice.getItemCount();
            ResultsTable res = new ResultsTable();
            Overlay ovr = new Overlay();
            double[] sum, sqSum;

            IJ.showStatus("Tracking "+rois.length+" cell"+(rois.length > 1 ? "s" : "")+" in "+frames+" frames ...");

            for (int frame = 0; frame < frames; frame++) { // for each frame
                IJ.showProgress(frame+1, frames);
                // setPosition requires 1-based indices !
                /* @TODO: maybe some day support for more then 1 slice could be added... */
                imp.setPosition(channelChoice.getSelectedIndex()+1, 1, frame+1); 

                sum = new double[channelCount];
                sqSum = new double[channelCount];

                if (!overlay) res.incrementCounter();

                for (var roiIndex = 0; roiIndex < rois.length; roiIndex++) { //for each ROI in the frame
                    Roi tmpRoi = rois[roiIndex];
                    imp.setRoi(tmpRoi);
                    if (frame > 0) { // on all but 1st frame regenerate the ROI from the center of the ROI
                        stat = imp.getStatistics(ij.measure.Measurements.CENTROID); // get current ROI statistics
                        tmpRoi = cmwt.makeRoi(
                            (int) Math.round(stat.xCentroid / calib.pixelWidth), 
                            (int) Math.round(stat.yCentroid / calib.pixelHeight), 
                            imp
                        ); // regenerate the ROI from the center of the ROI
                        rois[roiIndex] = tmpRoi; // write the regenerated ROI back to our ROI array
                    }
                    for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) { // apply the ROI to all channels
                        if (overlay) {
                            tmpRoi.setPosition(channelIndex+1, 1, frame+1);
                            ovr.add(tmpRoi);
                        } else {
                            imp.setPosition(channelIndex+1, 1, frame+1);
                            imp.setRoi(tmpRoi);
                            stat = imp.getStatistics(ij.measure.Measurements.MEAN);
                            res.addValue("Cell "+(roiIndex+1)+": "+channelChoice.getItem(channelIndex), stat.mean);
                            sum[channelIndex] += stat.mean;
                            sqSum[channelIndex] += (stat.mean * stat.mean);
                        }
                    }

                    if (!overlay) {
                        res.addValue("Cell "+(roiIndex+1)+": area", stat.area);
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
        //IJ.showMessage(e.getItem());

    }

} // class Cell_Track_and_Measure