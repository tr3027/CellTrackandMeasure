# Cell_Track_and_Measure plugin for ImageJ

This [ImageJ][IJ] plugin is inspired by the **Time_Series_Analyzer_V3** by **balaji** which, in its latest *Version 3_3 06th Aug 2008*, does not support tracking the ROIs in time. As my daughter needed to measure brain cells during a rather long time frame and as these cells tend to move around, the original plugin suffered. 

The **Cell_Track_and_Measure** plugin depends on the excellent [Cell Magic Wand Tool][cmwt] from Theo Walker from Max Plancks Florida Institute, which is used to detect the cell boundaries in each frame of the examined time series image in the background.

It tries to track cell ROIs in a user selectable channel of a time series image across all frames of that image. Each ROI, as generated in the tracking channel, is then used for all channels within the single frame.

The plugin provides the user with the possibility to double-check the tracking results by drawing the resulting ROIs in each channel/frame in an overlay image before grabbing the mean/area measurements accross all channels/frames. Finally, the measurements are shown in a results table.

# Compiling & installation

To build the plugin you need to have [ImageJ][IJ-git] and [Cell_Magic_Wand_Tool][cmwt] source code available on your computer. The provided **`build.sh`** file expects these to be present in **`lib/ij/`** and **`lib/cmwt/`** respectively. It builds the code and places the resulting JAR into the **`build/`** folder.

To install the plugin, just copy the JAR file into the **`plugins/`** folder of your [ImageJ][IJ] installation and restart [ImageJ][IJ].


[IJ]: (https://imagej.net/)
[IJ-git]: (https://github.com/imagej/ImageJ)
[cmwt]: (https://github.com/fitzlab/CellMagicWand)