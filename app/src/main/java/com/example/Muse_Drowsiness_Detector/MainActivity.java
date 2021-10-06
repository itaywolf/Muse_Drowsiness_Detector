package com.example.Muse_Drowsiness_Detector;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;

import java.net.SocketException;
import java.util.Date;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.illposed.osc.*;


public class MainActivity extends AppCompatActivity {

    Thread Thread1 = null;
    TextView tvPort, tvConnectionStatus, tvMessages, alarmText, tvDebug;

    // Communication variables
    public static final int SERVER_PORT = 50000;
    OSCPortIn receiver_eeg;
    String osc_method_eeg;
    MatlabOSCListener osc_listener_eeg;

    // General algorithm variables
    boolean start = false;
    int i = 0;
    double[] eegStruct;
    double[] TP9;
    double[] TP10;
    int TP9_SIZE = 100;
    int SIGNAL_SIZE = 100;
    Date timeStamp;
    long currTime;
    int can_start = 0;
    int width = 70; // have to be even
    double high_tresh = 860;
    double low_tresh = 650;
    double high_mul = 1.04;
    double low_mul = 0.84;
    long blink_tresh = 500; // msec
    int min_or_max = 1; // 0-expect for max, 1-expect for min
    int in_blink = 0;
    long blink_duration = 0;
    int low_idx, high_idx, curr_idx;
    long start_blink, end_blink;

    // "Avarage" feature variables
    double[] vals_for_avg;
    int VALS_FOR_AVG_SIZE = 1000;
    int vals_for_avg_idx;

    // "Connection" feature variables
    boolean connection = false;
    int conn_high_tresh = 1200;
    int conn_low_tresh = 400;
    int conn_cntr;
    int CONN_CNTR_SIZE = 700;
    int hist_high_tresh = 1000;
    int hist_low_tresh = 600;

    // "Backup sensor" feature variables
    int blink_state = 0;
    int TP9_BLINK = 1, TP10_BLINK = 2, FULL_BLINK = 3;
    int dist_cntr = -1;

    // Debug mode variables
    boolean debug_mode = false;

    // Sound variables
    MediaPlayer mp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Views setup
        setContentView(R.layout.activity_main);
        alarmText = findViewById(R.id.alarmText);
        tvPort = findViewById(R.id.tvPort);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvMessages = findViewById(R.id.tvMessages);
        tvDebug = findViewById(R.id.tvDebug);
        tvDebug.setText("Debug Mode: Off");

        // Buttons setup
        final Button startB = (Button) findViewById(R.id.startButton);
        final Button stopB = (Button) findViewById(R.id.stopButton);
        final Button debugB = (Button) findViewById(R.id.debugButton);

        // Media player setup
        mp = MediaPlayer.create(this, R.raw.ringtone);

        // Tell the program to start when clicking the start button
        startB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start = true;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (connection) {
                            tvConnectionStatus.setText("Connected");
                        }
                        else {
                            tvConnectionStatus.setText("Not Connected");
                        }
                    }
                });
            }
        });

        // Tell the program to stop when clicking the stop button
        stopB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvConnectionStatus.setText("Click the start button");
                    }
                });
            }
        });

        // Make the program show blink's length when clicking the debug button
        debugB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (debug_mode) {
                    debug_mode = false;
                    tvMessages.setText("");
                }
                else {
                    debug_mode = true;
                }

                if (debug_mode) {
                    tvDebug.setText("Debug Mode: On");
                }
                else {
                    tvDebug.setText("Debug Mode: Off");
                }
            }
        });

        // When the alarm finish get the media player ready for next time and clear the alarm text
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                mp.seekTo(0);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        alarmText.setText("");
                    }
                });
            }
        });

        // Run the algorithm
        Log.d("MyTag", "Starting");
        Thread1 = new Thread(new Thread1());
        Thread1.start();
    }

    class Thread1 implements Runnable {
        @RequiresApi(api = Build.VERSION_CODES.N)
        @Override
        public void run() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvConnectionStatus.setText("Click the start button");
                    tvPort.setText("Port: " + SERVER_PORT);
                }
            });

            // Listening on specified port to get the muse data sent from MindMonitor
            try {
                receiver_eeg = new OSCPortIn(SERVER_PORT);
            } catch (SocketException e1) {
                e1.printStackTrace();
            }
            osc_method_eeg = "/muse/eeg";
            osc_listener_eeg = new MatlabOSCListener();
            receiver_eeg.addListener(osc_method_eeg,osc_listener_eeg);
            Log.d("MyTag", "Start listening");
            receiver_eeg.startListening();

            // Initializing variables
            i = 0;
            TP9 = new double[SIGNAL_SIZE];
            TP10 = new double[SIGNAL_SIZE];
            vals_for_avg = new double[VALS_FOR_AVG_SIZE];
            vals_for_avg_idx = 0;

            // Algorithm main loop
            while (true) {
                // Don't run if the start button isn't clicked
                if (!start) {
                    continue;
                }

                // Get the eeg data
                eegStruct = osc_listener_eeg.getMessageArgumentsAsDouble();
                // Process the data only if it isn't null
                if (eegStruct != null) {
                    timeStamp = osc_listener_eeg.getTimeStamp();
                    currTime = timeStamp.getTime();
                    if (currTime == 0) {
                        continue;
                    }

                    // Get the values for TP9 and TP10 sensors
                    TP9[i] = eegStruct[0];
                    TP10[i] = eegStruct[3];

                    // Get the TP9 values for the average calculation
                    vals_for_avg[vals_for_avg_idx] = TP9[i];
                    vals_for_avg_idx = (vals_for_avg_idx == VALS_FOR_AVG_SIZE-1) ? 0 : vals_for_avg_idx + 1;

                    // Start the calculation only when we have enough data
                    if (can_start == 0 && i < width+1 && vals_for_avg_idx < VALS_FOR_AVG_SIZE-1) {
                        i = (i == TP9_SIZE-1) ? 0 : i+1;
                        continue;
                    }
                    else can_start = 1;

                    // Calculate connection
                    if (TP9[i] > conn_high_tresh || TP9[i] < conn_low_tresh) {
                        if (connection) {
                            min_or_max = 1;
                            in_blink = 0;
                            blink_duration = 0;
                            can_start = 0;
                            i = 0;
                            vals_for_avg_idx = 0;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tvConnectionStatus.setText("Not Connected");
                                }
                            });
                        }
                        connection = false;
                        conn_cntr = CONN_CNTR_SIZE;
                        continue;
                    }
                    else if (connection) tvConnectionStatus.setText("Connected");
                    else if (TP9[i] < hist_high_tresh && TP9[i] > hist_low_tresh) {
                        conn_cntr--;
                        tvConnectionStatus.setText("Not Connected\n");
                        if (conn_cntr == 0) {
                            connection = true;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tvConnectionStatus.setText("Connected");
                                }
                            });
                        }
                        else {
                            continue;
                        }
                    }
                    else {
                        conn_cntr = CONN_CNTR_SIZE;
                        tvConnectionStatus.setText("Not Connected");
                        continue;
                    }

                    // Calculate the current index we are using
                    curr_idx = (i-width/2 < 0) ? TP9_SIZE+i-width/2 : i-width/2;

                    // Set thresholds
                    high_tresh = getAvg(vals_for_avg) * high_mul;
                    low_tresh = getAvg(vals_for_avg) * low_mul;

                    if (min_or_max == 1) {
                        // Check for blink start in TP9 data
                        if (detectMin(TP9) == 1 && blink_state != TP9_BLINK) {
                            in_blink = 1;
                            start_blink = currTime;
                            blink_state += TP9_BLINK;
                            dist_cntr = 0;
                        }

                        // Check for blink start in TP10 data
                        if (detectMin(TP10) == 1 && blink_state != TP10_BLINK) {
                            blink_state += TP10_BLINK;
                            dist_cntr = 0;
                        }

                        if (dist_cntr != -1) dist_cntr++;

                        // Check for match between TP9 and TP10
                        if (dist_cntr > width/2) { //no match between 2 signals
                            blink_state = 0;
                            dist_cntr = -1;
                            in_blink = 0;
                            blink_duration = 0;
                        }
                        else if (blink_state == FULL_BLINK) { //match between the 2 signals!
                            blink_state = 0;
                            dist_cntr = -1;
                            min_or_max = 0;
                        }
                    }

                    if (min_or_max == 0) {
                        // Check for blink stop in TP9 data
                        if (detectMax(TP9) == 0) {
                            end_blink = currTime;
                            min_or_max = 1;
                            in_blink = 0;

                            // Check for long blink and alert the user of drowsiness
                            if (blink_duration > blink_tresh) {
                                try {
                                    if (mp.isPlaying()) {
                                        mp.pause();
                                        mp.seekTo(0);
                                    }
                                    mp.start();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                alarmText.setText("Be careful!!!\nYou are tired!");
                                if (debug_mode) tvMessages.setText("Long Blink ("+ blink_duration + "ms)");
                            }
                            else {
                                if (debug_mode) tvMessages.setText("Short Blink ("+ blink_duration + "ms)");
                            }
                            blink_duration = 0;
                        }
                    }

                    // Calculate blink time
                    if (in_blink == 1) {
                        blink_duration =  currTime - start_blink;
                    }

                    i = (i == TP9_SIZE-1) ? 0 : i+1;
                }
            } // End of algorithm main loop
        }

        double getAvg (double[] avgArray) {
            double sum = 0;

            for (int i=0 ; i<avgArray.length ; i++) {
                sum += avgArray[i];
            }

            return sum / avgArray.length;
        }

        int detectMin (double[] signal) {
            int is_min;

            // When the signal is lower than threshold we suspect it can be a minimum
            if (signal[curr_idx] < low_tresh && signal[curr_idx] > 0) {
                is_min = 1;
                // If one value of the surroundings of the suspected value is higher, then it's not a minimum
                for (int j=0 ; j<width/2 ; j++) {
                    low_idx = (curr_idx-j < 0) ? SIGNAL_SIZE+curr_idx-j : curr_idx-j;
                    high_idx = (curr_idx+j > SIGNAL_SIZE-1) ? curr_idx+j-SIGNAL_SIZE : curr_idx+j;
                    if (signal[curr_idx] > signal[low_idx] || signal[curr_idx] > signal[high_idx]) is_min = 0;
                }
                if (is_min == 1) { // Minimum detected
                    return 1;
                }
            }
            // Minimum not detected
            return 0;
        }

        int detectMax (double[] signal) {
            int is_max;

            // When the signal is higher than threshold we suspect it can be a maximum
            if (signal[curr_idx] > high_tresh) {
                is_max = 1;
                // If one value of the surroundings of the suspected value is lower, then it's not a maximum
                for (int j=0 ; j<width/2 ; j++) {
                    low_idx = (curr_idx-j < 0) ? SIGNAL_SIZE+curr_idx-j : curr_idx-j;
                    high_idx = (curr_idx+j > SIGNAL_SIZE-1) ? curr_idx+j-SIGNAL_SIZE : curr_idx+j;
                    if (signal[curr_idx] < signal[low_idx] || signal[curr_idx] < signal[high_idx]) is_max = 0;
                }
                if (is_max == 1) { // Maximum detected
                    return 0;
                }
            }
            // Maximum not detected
            return 1;
        }
    }
}