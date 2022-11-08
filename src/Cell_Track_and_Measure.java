import java.awt.*;
import java.awt.event.*;
// import java.util.*;
import ij.*;
import ij.Menus.*; // used to check if CellMagicWandTool available
// import ij.process.*;
import ij.gui.*;
// import ij.measure.ResultsTable;
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
                    createOverlay();
                    // vytvorit overlay
                    break;
                case Cell_Track_and_Measure.BTN_MEASURE:
                    measureCells();
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
                    // imp.getNChannels()       == 2
                    // imp.getImageStackSize()  == 664
                    // imp.getNDimensions()     == 4
                    // imp.getNFrames()         == 332
                    // imp.getNSlices()         == 1
                    // imp.getStackSize()       == 664
                    //IJ.showMessage(imp.getProp("[Channel 1 Parameters] DyeName"));
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

    public void createOverlay() {

    } // Cell_Track_and_Measure.createOverlay()

    public void measureCells() {

    } // Cell_Track_and_Measure.measureCells()

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