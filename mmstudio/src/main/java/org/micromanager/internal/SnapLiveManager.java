// Copyright (C) 2015-2017 Open Imaging, Inc.
//           (C) 2015 Regents of the University of California
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.internal;

import com.bulenkov.iconloader.IconLoader;
import com.google.common.eventbus.Subscribe;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import mmcorej.CMMCore;
import mmcorej.TaggedImage;
import org.json.JSONException;
import org.json.JSONObject;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.DatastoreFrozenException;
import org.micromanager.data.DatastoreRewriteException;
import org.micromanager.data.Image;
import org.micromanager.data.Metadata;
import org.micromanager.data.NewPipelineEvent;
import org.micromanager.data.Pipeline;
import org.micromanager.data.PipelineErrorException;
import org.micromanager.data.internal.DefaultImage;
import org.micromanager.data.internal.DefaultRewritableDatastore;
import org.micromanager.data.internal.StorageRAM;
import org.micromanager.display.ControlsFactory;
import org.micromanager.display.DisplaySettings;
import org.micromanager.display.DisplayWindow;
import org.micromanager.display.RequestToCloseEvent;
import org.micromanager.display.internal.DefaultDisplayManager;
import org.micromanager.display.internal.DefaultDisplaySettings;
import org.micromanager.display.internal.DisplayController;
import org.micromanager.events.internal.DefaultEventManager;
import org.micromanager.events.internal.DefaultLiveModeEvent;
import org.micromanager.events.internal.InternalShutdownCommencingEvent;
import org.micromanager.internal.interfaces.LiveModeListener;
import org.micromanager.internal.navigation.ClickToMoveManager;
import org.micromanager.internal.utils.GUIUtils;
import org.micromanager.internal.utils.MustCallOnEDT;
import org.micromanager.internal.utils.ReportingUtils;
import org.micromanager.internal.utils.ThreadFactoryFactory;
import org.micromanager.internal.utils.performance.PerformanceMonitor;
import org.micromanager.internal.utils.performance.gui.PerformanceMonitorUI;
import org.micromanager.quickaccess.internal.QuickAccessFactory;

/**
 * This class is responsible for all logic surrounding live mode and the
 * "snap image" display (which is the same display as that used for live mode).
 *
 * @author Chris Weisiger and Mark A. Tsuchida
 */
public final class SnapLiveManager implements org.micromanager.SnapLiveManager {
   private static final String TITLE = "Preview";

   private static final double MIN_GRAB_DELAY_MS = 1000.0 / 60.0;
   private static final double MAX_GRAB_DELAY_MS = 300.0;

   // What quantile of actual paint interval to use as the interval for image
   // retrieval. Too high will cause display rate to take a long time to climb
   // up to optimum. Too low can cause jittery display due to frames being
   // skipped.
   private static final double DISPLAY_INTERVAL_ESTIMATE_Q = 0.25;

   private final Studio studio_;
   private final CMMCore core_;
   private DisplayWindow display_;
   private DefaultRewritableDatastore store_;
   private Pipeline pipeline_;
   private final Object pipelineLock_ = new Object();
   private final ArrayList<LiveModeListener> listeners_ =
         new ArrayList<LiveModeListener>();
   private boolean isLiveOn_ = false;
   private final Object liveModeLock_ = new Object();
   private int numCameraChannels_ = -1;
   private boolean shouldForceReset_ = true;
   private boolean amStartingSequenceAcquisition_ = false;

   private final List<DefaultImage> lastImageForEachChannel_ =
         new ArrayList<DefaultImage>();

   private final ScheduledExecutorService scheduler_ =
         Executors.newSingleThreadScheduledExecutor(
               ThreadFactoryFactory.createThreadFactory("SnapLiveManager"));
   // Guarded by monitor on this
   private ScheduledFuture scheduledGrab_;
   // Counter for live acquisitions started, needed to synchronize across
   // a stopped and rapidly restarted run of live mode.
   // Guarded by monitor on this
   private long liveModeStartCount_ = 0;

   // As a (significant) convenience to our clients, we allow live mode to be
   // "suspended" and unsuspended, which amounts to briefly turning live mode
   // off if it is on, and then later turning it back on if it was on when
   // suspended. This gets unexpectedly complicated See setSuspended().
   private int suspendCount_ = 0;

   private final PerformanceMonitor perfMon_ =
         PerformanceMonitor.createWithTimeConstantMs(1000.0);
   private final PerformanceMonitorUI pmUI_ =
         PerformanceMonitorUI.create(perfMon_, "SnapLiveManager Performance");

   public SnapLiveManager(Studio studio, CMMCore core) {
      studio_ = studio;
      core_ = core;
      studio_.events().registerForEvents(this);
   }

   @Override
   public void setLiveMode(boolean isOn) {
      synchronized(liveModeLock_) {
         if (isLiveOn_ == isOn) {
            return;
         }
         isLiveOn_ = isOn;
         // Only actually start live mode now if we aren't currently
         // suspended.
         if (isLiveOn_ && suspendCount_ == 0) {
            startLiveMode();
         }
         else {
            stopLiveMode();
         }
         for (LiveModeListener listener : listeners_) {
            listener.liveModeEnabled(isLiveOn_);
         }
         DefaultEventManager.getInstance().post(new DefaultLiveModeEvent(isLiveOn_));
      }
   }

   /**
    * If live mode needs to temporarily stop for some action (e.g. changing
    * the exposure time), then clients can blindly call setSuspended(true)
    * to stop it and then setSuspended(false) to resume-only-if-necessary.
    * Note that this function will not notify listeners.
    * We need to handle the case where we get nested calls to setSuspended,
    * hence the reference count. And if we *are* suspended and someone tries
    * to start live mode (even though it wasn't running when we started the
    * suspension), then we should automatically start live mode when the
    * suspension ends. Thus, isLiveOn_ tracks the "nominal" state of live mode,
    * irrespective of whether or not it is currently suspended. When
    * suspendCount_ hits zero, we match up the actual state of live mode with
    * the nominal state.
    */
   @Override
   public void setSuspended(boolean shouldSuspend) {
      synchronized(liveModeLock_) {
         if (suspendCount_ == 0 && shouldSuspend && isLiveOn_) {
            // Need to stop now.
            stopLiveMode();
         }
         suspendCount_ += shouldSuspend ? 1 : -1;
         if (suspendCount_ == 0 && isLiveOn_) {
            // Need to resume now.
            startLiveMode();
         }
      }
   }

   private void startLiveMode() {
      if (amStartingSequenceAcquisition_) {
         // HACK: if startContinuousSequenceAcquisition results in a core
         // callback, then we can end up trying to start live mode when we're
         // already "in" startLiveMode somewhere above us in the call stack.
         // That is extremely prone to causing deadlocks between the image
         // grabber thread (which needs the Core camera lock) and our thread
         // (which already has the lock, due to
         // startContinuousSequenceAcquisition) -- and our thread is about to
         // join the grabber thread when stopLiveMode is called in a few lines.
         // Hence we use this sentinel value to check if we are actually
         // supposed to be starting live mode.
         studio_.logs().logDebugMessage("Skipping startLiveMode as startContinuousSequenceAcquisition is in process");
         return;
      }

      stopLiveMode(); // Make sure

      try {
         amStartingSequenceAcquisition_ = true;
         core_.startContinuousSequenceAcquisition(0);
         amStartingSequenceAcquisition_ = false;
      }
      catch (Exception e) {
         ReportingUtils.showError(e, "Couldn't start live mode sequence acquisition");
         // Give up on starting live mode.
         amStartingSequenceAcquisition_ = false;
         setLiveMode(false);
         return;
      }

      long coreCameras = core_.getNumberOfCameraChannels();
      if (coreCameras != numCameraChannels_) {
         // Number of camera channels has changed; need to reset the display.
         shouldForceReset_ = true;
      }
      numCameraChannels_ = (int) coreCameras;
      final double exposureMs;
      try {
         exposureMs = core_.getExposure();
      }
      catch (Exception e) {
         studio_.logs().showError(e, "Unable to determine exposure time");
         return;
      }
      final String camName = core_.getCameraDevice();

      synchronized (this) {
         lastImageForEachChannel_.clear();
      }

      if (display_ != null) {
         ((DisplayController) display_).resetDisplayIntervalEstimate();
      }

      synchronized (this) {
         final long liveModeCount = ++liveModeStartCount_;
         final Runnable grab;
         grab = new Runnable() {
            @Override
            public void run() {
               // We are started from within the monitor. Wait until that
               // monitor is released before starting.
               synchronized (SnapLiveManager.this) {
                  if (scheduledGrab_ == null ||
                        liveModeStartCount_ != liveModeCount) {
                     return;
                  }
               }
               grabAndAddImages(camName, liveModeCount);

               // Choose an interval within the absolute bounds, and at least as
               // long as the exposure. Within that range, try to match the
               // actual frequency at which the images are getting displayed.

               double displayIntervalLowQuantileMs;
               if (display_ != null) {
                  displayIntervalLowQuantileMs =
                        ((DisplayController) display_).
                              getDisplayIntervalQuantile(
                                    DISPLAY_INTERVAL_ESTIMATE_Q);
               }
               else {
                  displayIntervalLowQuantileMs = 0.0;
               }

               long delayMs;
               synchronized (SnapLiveManager.this) {
                  if (scheduledGrab_ == null ||
                        liveModeStartCount_ != liveModeCount) {
                     return;
                  }
                  delayMs = computeGrabDelayMs(exposureMs,
                        displayIntervalLowQuantileMs,
                        -scheduledGrab_.getDelay(TimeUnit.MILLISECONDS));
                  scheduledGrab_ = scheduler_.schedule(this,
                        delayMs, TimeUnit.MILLISECONDS);
               }
               perfMon_.sample("Grab schedule delay (ms)", delayMs);
            }
         };
         scheduledGrab_ = scheduler_.schedule(grab, 0, TimeUnit.MILLISECONDS);
      }

      if (display_ != null) {
         display_.toFront();
      }
   }

   private static long computeGrabDelayMs(double exposureMs,
         double displayIntervalMs, double alreadyElapsedMs)
   {
      double delayMs = Math.max(exposureMs, displayIntervalMs);
      delayMs -= alreadyElapsedMs;

      // Clip to allowed range
      delayMs = Math.max(MIN_GRAB_DELAY_MS, delayMs);
      if (delayMs > MAX_GRAB_DELAY_MS) {
         // A trick to get an interval that is less likely to frequently (and
         // noticeable) skip frames when the frame rate is low.
         delayMs /= Math.ceil(delayMs / MAX_GRAB_DELAY_MS);
      }

      return Math.round(delayMs);
   }

   private void stopLiveMode() {
      if (amStartingSequenceAcquisition_) {
         // HACK: if startContinuousSequenceAcquisition results in a core
         // callback, then we can end up trying to start live mode when we're
         // already "in" startLiveMode somewhere above us in the call stack.
         // See similar comment/block in startLiveMode(), above.
         studio_.logs().logDebugMessage("Skipping stopLiveMode as startContinuousSequenceAcquisition is in process");
         return;
      }

      synchronized (this) {
         if (scheduledGrab_ != null) {
            scheduledGrab_.cancel(false);
            scheduledGrab_ = null;
         }
      }

      try {
         if (core_.isSequenceRunning()) {
            core_.stopSequenceAcquisition();
         }
         while (core_.isSequenceRunning()) {
            core_.sleep(2);
         }
      }
      catch (Exception e) {
         ReportingUtils.showError(e, "Failed to stop sequence acquisition. Double-check shutter status.");
      }
   }

   /**
    * This method takes images out of the Core and inserts them into our
    * pipeline.
    */
   private void grabAndAddImages(String camName, final long liveModeCount) {
      try {
         // We scan over 2*numCameraChannels here because, in multi-camera
         // setups, one camera could be generating images faster than the
         // other(s). Of course, 2x isn't guaranteed to be enough here, either,
         // but it's what we've historically used.
         HashSet<Integer> channelsSet = new HashSet<Integer>();
         for (int c = 0; c < 2 * numCameraChannels_; ++c) {
            TaggedImage tagged;
            try {
               tagged = core_.getNBeforeLastTaggedImage(c);
               perfMon_.sampleTimeInterval("getNBeforeLastTaggedImage");
               perfMon_.sample("No image in sequence buffer (%)", 0.0);
            }
            catch (Exception e) {
               // No image in the sequence buffer.
               perfMon_.sample("No image in sequence buffer (%)", 100.0);
               continue;
            }
            JSONObject tags = tagged.tags;
            int imageChannel = c;
            if (tags.has(camName + "-CameraChannelIndex")) {
               imageChannel = tags.getInt(camName + "-CameraChannelIndex");
            }
            if (channelsSet.contains(imageChannel)) {
               // Already provided a more recent version of this channel.
               continue;
            }
            DefaultImage image = new DefaultImage(tagged);
            Long seqNr = image.getMetadata().getImageNumber();
            perfMon_.sample("Image missing ImageNumber (%)",
                  seqNr == null ? 100.0 : 0.0);
            Coords newCoords = image.getCoords().copy()
               .time(0)
               .channel(imageChannel).build();
            // Generate a new UUID for the image, so that our histogram
            // update code realizes this is a new image.
            Metadata newMetadata = image.getMetadata().copy()
               .uuid(UUID.randomUUID()).build();
            final Image newImage = image.copyWith(newCoords, newMetadata);
            try {
               SwingUtilities.invokeAndWait(new Runnable() {
                  @Override
                  public void run() {
                     synchronized (SnapLiveManager.this) {
                        if (scheduledGrab_ == null ||
                              liveModeStartCount_ != liveModeCount) {
                           throw new CancellationException();
                        }
                     }
                     displayImage(newImage);
                  }
               });
            }
            catch (InterruptedException unexpected) {
               Thread.currentThread().interrupt();
            }
            catch (InvocationTargetException e) {
               if (e.getCause() instanceof CancellationException) {
                  return;
               }
               throw new RuntimeException(e.getCause());
            }
            channelsSet.add(imageChannel);
            if (channelsSet.size() == numCameraChannels_) {
               // Got every channel.
               break;
            }
         }
      }
      catch (JSONException e) {
         ReportingUtils.logError(e, "Exception in image grabber thread.");
      }
   }

   public void addLiveModeListener(LiveModeListener listener) {
      if (!listeners_.contains(listener)) {
         listeners_.add(listener);
      }
   }

   public void removeLiveModeListener(LiveModeListener listener) {
      if (listeners_.contains(listener)) {
         listeners_.remove(listener);
      }
   }

   @Override
   public boolean getIsLiveModeOn() {
      return isLiveOn_;
   }

   /**
    * We need to [re]create the Datastore and its backing storage.
    */
   private void createDatastore() {
      synchronized(pipelineLock_) {
         if (pipeline_ != null) {
            pipeline_.halt();
         }
         // Note that unlike in most situations, we do *not* ask the
         // DataManager to track this Datastore for us.
         store_ = new DefaultRewritableDatastore();
         store_.setStorage(new StorageRAM(store_));
         // Use a synchronous pipeline for live mode.
         pipeline_ = studio_.data().copyLivePipeline(store_, true);
      }
   }

   private void createDisplay() {
      ControlsFactory controlsFactory = new ControlsFactory() {
         @Override
         public List<Component> makeControls(DisplayWindow display) {
            return createControls(display);
         }
      };
      display_ = new DisplayController.Builder(store_).
            controlsFactory(controlsFactory).
            settingsProfileKey(TITLE).
            shouldShow(true).build();
      DefaultDisplayManager.getInstance().addViewer(display_);

      // HACK: coerce single-camera setups to grayscale (instead of the
      // default of composite mode) if there is no existing profile settings
      // for the user and we do not have a multicamera setup.
      DisplaySettings.ColorMode mode = DefaultDisplaySettings.getStandardColorMode(TITLE, null);
      if (mode == null && numCameraChannels_ == 1) {
         DisplaySettings settings = display_.getDisplaySettings();
         settings = settings.copyBuilder()
            .colorMode(DisplaySettings.ColorMode.GRAYSCALE)
            .build();
         display_.setDisplaySettings(settings);
      }
      display_.registerForEvents(this);
      display_.setCustomTitle(TITLE);
   }

   /**
    * HACK: in addition to providing the snap/live/album buttons for the
    * display, we also set it up for click-to-move at this point.
    * We do this because duplicates of the snap/live window also need
    * click-to-move to be enabled.
    */
   private List<Component> createControls(final DisplayWindow display) {
      /* TODO
      ClickToMoveManager.getInstance().activate((DisplayController) display);
      */
      ArrayList<Component> controls = new ArrayList<Component>();
      Insets zeroInsets = new Insets(0, 0, 0, 0);
      Dimension buttonSize = new Dimension(90, 28);

      JComponent snapButton = QuickAccessFactory.makeGUI(
            studio_.plugins().getQuickAccessPlugins().get(
               "org.micromanager.quickaccess.internal.controls.SnapButton"));
      snapButton.setPreferredSize(buttonSize);
      snapButton.setMinimumSize(buttonSize);
      controls.add(snapButton);

      JComponent liveButton = QuickAccessFactory.makeGUI(
            studio_.plugins().getQuickAccessPlugins().get(
               "org.micromanager.quickaccess.internal.controls.LiveButton"));
      liveButton.setPreferredSize(buttonSize);
      liveButton.setMinimumSize(buttonSize);
      controls.add(liveButton);

      JButton toAlbumButton = new JButton("Album",
            IconLoader.getIcon(
               "/org/micromanager/icons/camera_plus_arrow.png"));
      toAlbumButton.setToolTipText("Add the current image to the Album collection");
      toAlbumButton.setPreferredSize(buttonSize);
      toAlbumButton.setMinimumSize(buttonSize);
      toAlbumButton.setFont(GUIUtils.buttonFont);
      toAlbumButton.setMargin(zeroInsets);
      toAlbumButton.addActionListener(new ActionListener() {
         @Override
         public void actionPerformed(ActionEvent event) {
            // Send all images at current channel to the album.
            Coords.CoordsBuilder builder = studio_.data().getCoordsBuilder();
            for (int i = 0; i < store_.getAxisLength(Coords.CHANNEL); ++i) {
               builder.channel(i);
               DefaultAlbum.getInstance().addImages(store_.getImagesMatching(
                     builder.build()));
            }
         }
      });
      controls.add(toAlbumButton);
      return controls;
   }

   /**
    * Display the provided image. Due to limitations of ImageJ, if the image's
    * parameters (width, height, or pixel type) change, we have to recreate
    * the display and datastore. We also do this if the channel names change,
    * as an inefficient way to force the channel colors to update.
    * @param image Image to be displayed
    */
   @Override
   public void displayImage(final Image image) {
      if (!SwingUtilities.isEventDispatchThread()) {
         SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
               displayImage(image);
            }
         });
      }

      boolean shouldReset = shouldForceReset_;
      if (store_ != null) {
         String[] channelNames = store_.getSummaryMetadata().getChannelNames();
         String curChannel = "";
         try {
            curChannel = core_.getCurrentConfig(core_.getChannelGroup());
         }
         catch (Exception e) {
            ReportingUtils.logError(e, "Error getting current channel");
         }
         for (int i = 0; i < numCameraChannels_; ++i) {
            String name = makeChannelName(curChannel, i);
            if (channelNames == null ||
                  i >= channelNames.length ||
                  !name.equals(channelNames[i]))
            {
               // Channel name changed.
               shouldReset = true;
            }
         }
      }
      try {
         DefaultImage newImage = new DefaultImage(image, image.getCoords(),
               studio_.acquisitions().generateMetadata(image, true));

         int newImageChannel = newImage.getCoords().getChannel();
         DefaultImage lastImage = null;
         synchronized (this) {
            lastImage = lastImageForEachChannel_.size() > newImageChannel ?
                  lastImageForEachChannel_.get(newImageChannel) :
                  null;
         }

         if (lastImage != null &&
               (newImage.getWidth() != lastImage.getWidth() ||
               newImage.getHeight() != lastImage.getHeight() ||
               newImage.getNumComponents() != lastImage.getNumComponents() ||
               newImage.getBytesPerPixel() != lastImage.getBytesPerPixel()))
         {
            // Format changing, channel changing, and/or we have no display;
            // we need to recreate everything.
            shouldReset = true;
         }
         else if (lastImage != null) {
            Long prevSeqNr = lastImage.getMetadata().getImageNumber();
            Long newSeqNr = newImage.getMetadata().getImageNumber();
            if (prevSeqNr != null && newSeqNr != null) {
               if (prevSeqNr >= newSeqNr)
               {
                  perfMon_.sample(
                        "Image rejected based on ImageNumber (%)", 100.0);
                  return; // Already displayed this image
               }
               perfMon_.sample("Frames dropped at sequence buffer exit (%)",
                     100.0 * (newSeqNr - prevSeqNr - 1) / (newSeqNr - prevSeqNr));
            }
         }
         perfMon_.sample("Image rejected based on ImageNumber (%)", 0.0);

         if (shouldReset) {
            createOrResetDatastoreAndDisplay();
         }
         // Check for display having been closed on us by the user.
         else if (display_ == null || display_.isClosed()) {
            createDisplay();
         }

         synchronized (this) {
            if (lastImageForEachChannel_.size() > newImageChannel) {
               lastImageForEachChannel_.set(newImageChannel, newImage);
            }
            else {
               lastImageForEachChannel_.add(newImageChannel, newImage);
            }
         }

         synchronized(pipelineLock_) {
            try {
               pipeline_.insertImage(newImage);
               perfMon_.sampleTimeInterval("Image inserted in pipeline");
            }
            catch (DatastoreRewriteException e) {
               // This should never happen, because we use an erasable
               // Datastore.
               studio_.logs().showError(e,
                     "Unable to insert image into pipeline; this should never happen.");
            }
            catch (PipelineErrorException e) {
               // Notify the user, and halt live.
               studio_.logs().showError(e,
                     "An error occurred while processing images.");
               stopLiveMode();
               pipeline_.clearExceptions();
            }
         }
      }
      catch (DatastoreFrozenException e) {
         // Datastore has been frozen (presumably the user saved a snapped
         // image); replace it.
         createOrResetDatastoreAndDisplay();
         displayImage(image);
      }
      catch (Exception e) {
         // Error getting metadata from the system state cache.
         studio_.logs().logError(e, "Error drawing image in snap/live view");
      }
   }

   @MustCallOnEDT
   private void createOrResetDatastoreAndDisplay() {
      if (numCameraChannels_ == -1) {
         numCameraChannels_ = (int) core_.getNumberOfCameraChannels();
      }

      // Remember the position of the window.
      Point displayLoc = null;
      if (display_ != null && !display_.isClosed()) {
         displayLoc = display_.getWindow().getLocation();
         display_.close();
      }

      createDatastore();
      createDisplay();
      if (displayLoc != null) {
         display_.getWindow().setLocation(displayLoc);
      }

      synchronized (this) {
         lastImageForEachChannel_.clear();
      }

      // Set up the channel names in the store's summary metadata. This will
      // as a side-effect ensure that our channels are displayed with the
      // correct colors.
      try {
         String channel = core_.getCurrentConfig(core_.getChannelGroup());
         String[] channelNames = new String[numCameraChannels_];
         for (int i = 0; i < numCameraChannels_; ++i) {
            channelNames[i] = makeChannelName(channel, i);
         }
         try {
            store_.setSummaryMetadata(store_.getSummaryMetadata().copy()
                  .channelNames(channelNames).build());
         }
         catch (DatastoreFrozenException e) {
            ReportingUtils.logError(e,
                  "Unable to update store summary metadata");
         }
      }
      catch (Exception e) {
         ReportingUtils.logError(e, "Error getting channel name");
      }
      shouldForceReset_ = false;
   }

   /**
    * Make a name up for the given channel/camera number combination.
    */
   private String makeChannelName(String channel, int cameraIndex) {
      String result = channel;
      if (numCameraChannels_ > 1) {
         result = result + " " + cameraIndex;
      }
      return result;
   }

   /**
    * Snap an image, display it if indicated, and return it.
    */
   @Override
   public List<Image> snap(boolean shouldDisplay) {
      if (isLiveOn_) {
         // Just return the most recent images.
         // BUG: In theory this could transiently contain nulls
         synchronized (this) {
            return new ArrayList<Image>(lastImageForEachChannel_);
         }
      }
      try {
         List<Image> images = studio_.acquisitions().snap();
         if (shouldDisplay) {
            if (display_ != null) {
               ((DisplayController) display_).resetDisplayIntervalEstimate();
            }
            for (Image image : images) {
               displayImage(image);
            }
            display_.toFront();
         }
         return images;
      }
      catch (Exception e) {
         ReportingUtils.showError(e, "Failed to snap image");
      }
      return null;
   }

   @Override
   public DisplayWindow getDisplay() {
      if (display_ == null || display_.isClosed()) {
         return null;
      }
      return display_;
   }

   @Subscribe
   public void onRequestToClose(RequestToCloseEvent event) {
      // Closing is fine by us, but we need to stop live mode first.
      setLiveMode(false);
      event.getDisplay().forceClosed();
      // Force a reset for next time, in case of changes that we don't pick up
      // on (e.g. a processor that failed to notify us of changes in image
      // parameters.
      shouldForceReset_ = true;
   }

   @Subscribe
   public void onPipelineChanged(NewPipelineEvent event) {
      // This will make us pick up the new pipeline the next time we get a
      // chance.
      shouldForceReset_ = true;
   }

   @Subscribe
   public void onShutdownCommencing(InternalShutdownCommencingEvent event) {
      if (!event.getIsCancelled()) {
         setLiveMode(false);
      }
   }
}
