package de.uni_weimar.benike.joggernaut;
/*
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
*/

/*

    Abszisse berechnen sich:
    Tick-Distance auf der X-Achse ist (Samplingrate / 2) / (Window-Size / 2) oder Samplingrate / Window-Size
 */



import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.androidplot.util.PlotStatistics;
import com.androidplot.xy.BarFormatter;
import com.androidplot.xy.BarRenderer;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.XYStepMode;

import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Arrays;

/* The following code snippets have been used as a reference:
    http://androidplot.com/docs/a-dynamic-xy-plot/
    https://developer.android.com/guide/topics/sensors/sensors_motion.html#sensors-motion-accel
    icons from Google under CC-BY 4.0 (https://creativecommons.org/licenses/by/4.0/#):
    https://design.google.com/icons/
 */


public class MainActivity extends Activity
        implements SeekBar.OnSeekBarChangeListener,
                    SensorEventListener,
                    LocationListener

{
    private static final String TAG = MainActivity.class.getName();

    private XYPlot mAccelerometerPlot = null;
    private XYPlot mFftPlot = null;

    private static int SAMPLE_MIN_VALUE = 0;
    private static int SAMPLE_MAX_VALUE = 200;
    private static int SAMPLE_STEP = 10;

    private static int WINDOW_MIN_VALUE = 5;
    private static int WINDOW_MAX_VALUE = 8;
    private static int WINDOW_STEP = 1;

    private int interval = SAMPLE_MIN_VALUE;


    private SimpleXYSeries mAccelerometerXSeries = null;
    private SimpleXYSeries mAccelerometerYSeries = null;
    private SimpleXYSeries mAccelerometerZSeries = null;
    private SimpleXYSeries mAccelerometerMSeries = null;

    private ArrayList<Double> mFftAverages = new ArrayList<>();
    private static final int LEN_AVERAGES = 128;

    private SimpleXYSeries mFftSeries = null;

    private int mWindowSize = 32;            // number of points to plot in history
    private SensorManager mSensorManager = null;
    private Sensor mAccelerometerSensor = null;

    private SeekBar mSampleRateSeekBar;
    private SeekBar mFftWindowSeekBar;

    private FFT mFft;
    private Thread mFftThread;
    private Thread mDetectActivityThread;

    private static final int mNotificationId = 42;

    private NotificationManager mNotificationManager = null;
    private NotificationCompat.Builder mNotificationBuilder = null;

    private static final double ACTIVITY_THRESHOLD_WALKING = 14.5;
    private static final double ACTIVITY_THRESHOLD_RUNNING = 19.7;

    private TextView mWindowSizeTextView;
    private int mSampleRateUS = 20000;
    private NumberFormat mNumberFormat = new NumberFormat() {
        private NumberFormat formatter = new DecimalFormat("#0.0");
        @Override
        public StringBuffer format(double d, StringBuffer sb, FieldPosition fp) {
            double maxFrequency = (1.0 / mSampleRateUS * 1000000)
                    ;
            double hz = (d / mWindowSize) * maxFrequency;

            return sb.append(formatter.format(hz)+ "Hz"); // shortcut to convert d+1 into a String
        }

        // unused
        @Override
        public StringBuffer format(long l, StringBuffer stringBuffer, FieldPosition fieldPosition) { return null;}

        // unused
        @Override
        public Number parse(String s, ParsePosition parsePosition) { return null;}
    };
    private LocationManager mLocationManager;
    private float mSpeed = 0.0f;
    private DetectActivity mDetectActivity;
    private double mFftMax = 0.0;
    private double mFftE = 0.0;
    private double mFftPeak = 0;


    public enum ActivityState { UNSURE, STANDING, WALKING, RUNNING, CYCLING, DRIVING };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // register for accelerometer events:
        mSensorManager = (SensorManager) getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
        for (Sensor sensor : mSensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER)) {
            if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                mAccelerometerSensor = sensor;
            }
        }

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startLocationServices();

        // if we can't access the accelerometer sensor then exit:
        if (mAccelerometerSensor == null) {
            Log.e(TAG, "Failed to attach to Accelerator Sensor.");
            Toast.makeText(this, "Error! Failed to create accelerometer sensor!", Toast.LENGTH_LONG)
                    .show();
            cleanup();
        }

        //mSensorManager.registerListener(this, mAccelerometerSensor, SensorManager.SENSOR_DELAY_UI);
        //ChangeSampleRate(SensorManager.SENSOR_DELAY_UI);
        ChangeSampleRate(mSampleRateUS);

        // setup the Accelerometer History plot:
        mAccelerometerPlot = (XYPlot) findViewById(R.id.accelerometerPlot);
        mFftPlot = (XYPlot) findViewById(R.id.fftPlot);

        mAccelerometerPlot.setRangeBoundaries(-25, 25, BoundaryMode.FIXED);

        mAccelerometerPlot.setDomainBoundaries(0, mWindowSize - 1, BoundaryMode.FIXED);

        //mFftPlot.setDomainBoundaries(0, 20, BoundaryMode.FIXED);
        //mFftPlot.setDomainRightMax(20);

        BarFormatter bf = new BarFormatter(Color.DKGRAY, Color.WHITE);

        mFftSeries = new SimpleXYSeries("FFT");
        mFftSeries.useImplicitXVals();

        mFftPlot.addSeries(mFftSeries, bf);
        //mFftPlot.addSeries(mFftSeries,
        //        new LineAndPointFormatter(Color.rgb(0, 0, 0), null, null, null));

        //mFftPlot.setRangeBoundaries(0, 350, BoundaryMode.FIXED);
        mFftPlot.setRangeBoundaries(0, 250, BoundaryMode.FIXED);

        mFftPlot.setRangeStep(XYStepMode.SUBDIVIDE, 1);
        //mFftPlot.
        // Boundaries(0, mWindowSize / 2, BoundaryMode.AUTO);
        mFftPlot.setDomainStep(XYStepMode.SUBDIVIDE, 10);
        //mFftPlot.setDomainBoundaries(0, 20, BoundaryMode.AUTO);
        //mFftPlot.setDomainRightMax(20);

        mFftPlot.setDomainValueFormat(mNumberFormat);

        BarRenderer renderer = (BarRenderer) mFftPlot.getRenderer(BarRenderer.class);
        renderer.setBarWidthStyle(BarRenderer.BarWidthStyle.VARIABLE_WIDTH);
        //renderer.setBarWidthStyle(BarRenderer.BarWidthStyle.FIXED_WIDTH);

        //float width = mFftPlot.getGraphWidget().getGridDimensions().canvasRect.width();
        //renderer.setBarWidth(width);

        ////mFftPlot.setRangeStep(XYStepMode.INCREMENT_BY_VAL, 50);

        mAccelerometerXSeries = new SimpleXYSeries("X");
        mAccelerometerXSeries.useImplicitXVals();
        mAccelerometerYSeries = new SimpleXYSeries("Y");
        mAccelerometerYSeries.useImplicitXVals();
        mAccelerometerZSeries = new SimpleXYSeries("Z");
        mAccelerometerZSeries.useImplicitXVals();
        mAccelerometerMSeries = new SimpleXYSeries("magnitude");
        mAccelerometerMSeries.useImplicitXVals();



        mAccelerometerPlot.addSeries(mAccelerometerXSeries,
                new LineAndPointFormatter(Color.rgb(100, 100, 200), null, null, null));
        mAccelerometerPlot.addSeries(mAccelerometerYSeries,
                new LineAndPointFormatter(Color.rgb(100, 200, 100), null, null, null));
        mAccelerometerPlot.addSeries(mAccelerometerZSeries,
                new LineAndPointFormatter(Color.rgb(200, 100, 100), null, null, null));
        mAccelerometerPlot.addSeries(mAccelerometerMSeries,
                new LineAndPointFormatter(Color.rgb(0, 0, 0), null, null, null));
        mAccelerometerPlot.setDomainStepValue(5);
        mAccelerometerPlot.setTicksPerRangeLabel(3);
        mAccelerometerPlot.setDomainLabel("Sample Index");
        mAccelerometerPlot.getDomainLabelWidget().pack();
        mAccelerometerPlot.setRangeLabel("m/s^2");
        mAccelerometerPlot.getRangeLabelWidget().pack();

        final PlotStatistics histStats = new PlotStatistics(1000, false);
        mAccelerometerPlot.addListener(histStats);

        // perform hardware accelerated rendering of the plots
        mAccelerometerPlot.setLayerType(View.LAYER_TYPE_NONE, null);
        mFftPlot.setLayerType(View.LAYER_TYPE_NONE, null);

        mFftPlot.setTicksPerRangeLabel(5);
        mFftPlot.setTicksPerDomainLabel(1);

        mSampleRateSeekBar = (SeekBar) findViewById(R.id.sampleRateSeekBar);
        mSampleRateSeekBar.setMax((SAMPLE_MAX_VALUE - SAMPLE_MIN_VALUE) / SAMPLE_STEP);

        mSampleRateSeekBar.setOnSeekBarChangeListener(this);

        mFftWindowSeekBar = (SeekBar) findViewById(R.id.fftWindowSeekBar);
        mFftWindowSeekBar.setMax((WINDOW_MAX_VALUE - WINDOW_MIN_VALUE) / WINDOW_STEP);
        mFftWindowSeekBar.setOnSeekBarChangeListener(this);

        mWindowSizeTextView = (TextView) findViewById(R.id.windowSizeTextView);

        // Perform FFT calculations in background thread
        mFft = new FFT(mWindowSize);
        Runnable r = new PerformFft();
        mFftThread = new Thread(r);
        mFftThread.start();

        mNotificationBuilder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_help_outline_black_24dp)
                .setContentTitle("MIS Ex3 Activity Recognizer")
                .setContentText("Trying to guess your activity")
                .setOngoing(true);

        Intent resultIntent = new Intent(this, MainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
        mNotificationBuilder.setContentIntent(resultPendingIntent);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(mNotificationId, mNotificationBuilder.build());

        mDetectActivity = new DetectActivity();
        mDetectActivityThread = new Thread(mDetectActivity);
        mDetectActivityThread.start();
    }


    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private void startLocationServices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission to access the location is missing.
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            // Access to the location has been granted to the app.
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 4.0f, this);
        }
    }

    private void resetSeries() {

        while (mAccelerometerMSeries.size() > mWindowSize) {
            mAccelerometerXSeries.removeFirst();
            mAccelerometerYSeries.removeFirst();
            mAccelerometerZSeries.removeFirst();
            mAccelerometerMSeries.removeFirst();
        }


        mAccelerometerPlot.redraw();
    }

    @Override
    public void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, mAccelerometerSensor,
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar == mSampleRateSeekBar) {
            Log.d(TAG, "Progress: " + progress);
            progress = seekBar.getMax() - progress;
            int value = SAMPLE_MIN_VALUE + progress * SAMPLE_STEP;
            Log.d(TAG, "Samplesize SeekBar value: " + value);
            ChangeSampleRate(value * 1000);
        } else if (seekBar == mFftWindowSeekBar) {
            int value = (int) Math.pow(2, WINDOW_MIN_VALUE + progress * WINDOW_STEP);
            Log.d(TAG, "Windowsize SeekBar value: " + value);

            mWindowSize = value;
            mAccelerometerPlot.setDomainBoundaries(0, mWindowSize - 1, BoundaryMode.FIXED);
            //mFftPlot.setDomainBoundaries(0, 20, BoundaryMode.AUTO);
            //mFftPlot.setDomainRightMax(20);

            mFft = new FFT(mWindowSize);
            resetSeries();
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
    }


    public void ChangeSampleRate(int us) {
        mSampleRateUS = us;
        Log.d(TAG, "Samplerate value: " + us);
        mSensorManager.unregisterListener(this);
        mSensorManager.registerListener(this, mAccelerometerSensor, us);
    }

    private void cleanup() {
        // unregister with the orientation sensor before exiting:
        mDetectActivity.shutdown();
        mSensorManager.unregisterListener(this);
        mLocationManager.removeUpdates(this);
        mNotificationManager.cancelAll();
    }

    @Override
    protected void onDestroy() {

        cleanup();
        super.onDestroy();
    }

    // Called whenever a new accelSensor reading is taken.
    @Override
    public synchronized void onSensorChanged(SensorEvent sensorEvent) {

        // update instantaneous data:
        Number[] series1Numbers = {sensorEvent.values[0], sensorEvent.values[1], sensorEvent.values[2]};


        // get rid the oldest sample in history:
        if (mAccelerometerXSeries.size() > mWindowSize - 1) {
            mAccelerometerXSeries.removeFirst();
            mAccelerometerYSeries.removeFirst();
            mAccelerometerZSeries.removeFirst();
            mAccelerometerMSeries.removeFirst();
        }

        // add the latest history sample:
        final float accelXdata = sensorEvent.values[0];
        final float accelYdata = sensorEvent.values[1];
        final float accelZdata = sensorEvent.values[2];
        mAccelerometerXSeries.addLast(null, accelXdata);
        mAccelerometerYSeries.addLast(null, accelYdata);
        mAccelerometerZSeries.addLast(null, accelZdata);
        mAccelerometerMSeries.addLast(null, Math.sqrt(accelXdata * accelXdata
                + accelYdata * accelYdata + accelZdata * accelZdata) /* - 9.81 */
        );

        //Log.d(TAG, "Sample added. Size of m series: " + mAccelerometerMSeries.size());

        // redraw the Plots
        mAccelerometerPlot.redraw();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // Not interested in this event
    }

    private void updateNotification(ActivityState activityState) {


        if (activityState == ActivityState.STANDING) {
            mNotificationBuilder
                    .setSmallIcon(R.drawable.ic_accessibility_black_24dp)
                    .setContentText("You are standing/sitting");
        } else if (activityState == ActivityState.WALKING) {
            mNotificationBuilder
                    .setSmallIcon(R.drawable.ic_directions_walk_black_24dp)
                    .setContentText("You are walking");
        } else if (activityState == ActivityState.RUNNING) {
            mNotificationBuilder
                    .setSmallIcon(R.drawable.ic_accessibility_black_24dp)
                    .setContentText("You are running");
        } else if (activityState == ActivityState.CYCLING) {
            mNotificationBuilder
                    .setSmallIcon(R.drawable.ic_bicycle_blac_24dp)
                    .setContentText("You are cycling");
        } else if (activityState == ActivityState.DRIVING) {
            mNotificationBuilder
                    .setSmallIcon(R.drawable.ic_car_black_24dp)
                    .setContentText("You are driving");
        } else { // unsure
            mNotificationBuilder
                    .setSmallIcon(R.drawable.ic_help_outline_black_24dp)
                    .setContentText("Could not determine your activity...");
        }

        mNotificationManager.notify(
                mNotificationId,
                mNotificationBuilder.build());
    }

    boolean doubleBackToExitPressedOnce = false;

    @Override
    public void onBackPressed() {
        if (doubleBackToExitPressedOnce) {
            Toast.makeText(this, "Pressed twice!", Toast.LENGTH_SHORT).show();
            super.onBackPressed();
            finish();
            return;
        }

        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show();

        new Handler().postDelayed(new Runnable() {

            @Override
            public void run() {
                doubleBackToExitPressedOnce=false;
            }
        }, 2000);
    }

    private class PerformFft implements Runnable {

        private Handler mFftHandler = new Handler();

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            if (mAccelerometerMSeries.size() == mWindowSize) {
                double re[] = new double[mWindowSize];
                double im[] = new double[mWindowSize];
                double mean = 0.0;

                for (int i = 0; i < mAccelerometerMSeries.size(); ++i) {
                    mean += (double) mAccelerometerMSeries.getY(i);
                }
                mean /= mWindowSize;

                for (int i = 0; i < mAccelerometerMSeries.size(); ++i) {
                    re[i] = (double) mAccelerometerMSeries.getY(i) - mean ;
                    re[i] = ((0.53836 - (0.46164 * Math.cos(2 * Math.PI * i  /
                            ( mWindowSize - 1 )))) * re[i]);
                    im[i] = 0.0;
                }
                mFft.fft(re, im);

                final Number magnitude[] = new Number[mWindowSize / 2];
                double fftMax = 0.0;
                int peakIndex = 0;
                double fftE = 0.0;
                for (int i = 0; i < mWindowSize / 2; ++i) {
                    double value = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
                    magnitude[i] = value;
                    fftE += value;
                    if (value > fftMax) {
                        fftMax = value;
                        peakIndex = i;
                    }
                }


                // update field values
                mFftMax = fftMax;
                mFftE = fftE;
                double maxFrequency = (1.0 / mSampleRateUS * 1000000);
                double hz = (peakIndex / mWindowSize) * maxFrequency;
                mFftPeak = hz;

                /*
                for (int i = 0; i < mWindowSize / 2; ++i) {
                    magnitude[i] = magnitude[i].doubleValue() / fftMax;
                } */

                // Plot our magnitude on
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mFftSeries.setModel(Arrays.asList(magnitude), SimpleXYSeries.ArrayFormat.Y_VALS_ONLY);
                        mFftPlot.redraw();
                    }
                });

                Double sum = 0.0;
                for (Number n : magnitude) sum += (Double) n;
                Double average = sum / magnitude.length;

                if (mFftAverages.size() > LEN_AVERAGES - 1) {
                    mFftAverages.remove(0);
                }
                mFftAverages.add(average);
            }
            mFftHandler.post(this);
        }
    }

    private class DetectActivity implements Runnable {

        private Handler mActivityHandler = new Handler();

        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            double energylevel = mFftMax / mFftE;
            Log.d(TAG, "Energylevel: " + energylevel);
            Log.d(TAG, "Speed: " + mSpeed);

            final double ACTIVITY_THRESHOLD = 0.135;
            final double WALKING_SPEED = 0.7;

            if (energylevel > ACTIVITY_THRESHOLD) { // device is above
                if (mSpeed > 0.7 && mSpeed <= 1.3) {
                    Log.d(TAG, "Activity detected: walking");
                    updateNotification(ActivityState.WALKING);
                } else if (mSpeed > 1.3 && mSpeed < 9 && energylevel > 0.2) {
                    Log.d(TAG, "Activity detected: running");
                    updateNotification(ActivityState.RUNNING);
                } else if (mSpeed > 1.3 && mSpeed < 20 && energylevel < 0.25) {
                    Log.d(TAG, "Activity detected: cycling");
                    updateNotification(ActivityState.CYCLING);
                }
                else {
                    Log.d(TAG, "Activity detected: not sure");
                    updateNotification(ActivityState.UNSURE);
                }
            } else {
                if (mSpeed < 0.7) {
                    Log.d(TAG, "Activity detected: sitting/standing");
                    updateNotification(ActivityState.STANDING);
                } else {
                    Log.d(TAG, "Activity detected: driving");
                    updateNotification(ActivityState.DRIVING);
                }
            }

            /*
            if (mFftAverages.size() == LEN_AVERAGES) {
                // calculate complete average
                Double sum = 0.0;
                for (Double d : mFftAverages) sum += d;
                Double average = sum / LEN_AVERAGES;
                // Log.i(TAG, "Activity fft freq average: " + average);
                // we used adb wifi debugging to watch the logcat to determine thresholds for
                // different walking speeds (a bit problematic is the shift with different samplerates)
                if (average < ACTIVITY_THRESHOLD_WALKING) {
                    Log.d(TAG, "Activity detected: sitting/standing");
                    updateNotification(ActivityState.STANDING);
                }
                else if (average > ACTIVITY_THRESHOLD_WALKING
                        && average < ACTIVITY_THRESHOLD_RUNNING) {
                    Log.d(TAG, "Activity detected: walking");
                    updateNotification(ActivityState.WALKING);
                }
                else if (average > ACTIVITY_THRESHOLD_RUNNING) {
                    Log.d(TAG, "Activity detected: running");
                    updateNotification(ActivityState.RUNNING);
                }
                else {
                    updateNotification(ActivityState.UNSURE);
                }
                Log.d(TAG, "Activity FFT average: " + average);
                mFftAverages.clear();
            }
            */
            mActivityHandler.postDelayed(this, 5000);
        }

        public void shutdown() {
            mActivityHandler.removeCallbacksAndMessages(null);
        }
    }



    @Override
    public void onLocationChanged(Location location) {
        Log.d(TAG, "Location changed: " + location);

        mSpeed = location.getSpeed();
        Toast.makeText(this, "Location changed. Speed: " + mSpeed, Toast.LENGTH_SHORT)
                .show();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }
}
