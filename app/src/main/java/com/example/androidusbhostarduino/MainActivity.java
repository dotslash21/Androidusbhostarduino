package com.example.androidusbhostarduino;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import android.Manifest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

public class MainActivity extends AppCompatActivity implements Runnable, GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, LocationListener {

    private static final String TAG = "AndroidUsbHostArduino";

    private static final byte IGNORE_00 = (byte) 0x00;
    private static final byte SYNC_WORD = (byte) 0xFF;

    private static final int CMD_LED_OFF = 2;
    private static final int CMD_LED_ON = 1;
    private static final int CMD_TEXT = 3;
    private static final int MAX_TEXT_LENGTH = 16;

    ToggleButton buttonLed;
    EditText textOut;
    Button buttonSend;
    Button buttonSendLocation;
    Button buttonGetLocation;
    TextView textIn;
    String stringToRx;

    private double longitude;
    private double latitude;

    private UsbManager usbManager;
    private UsbDevice deviceFound;
    private UsbDeviceConnection usbDeviceConnection;
    private UsbInterface usbInterfaceFound = null;
    private UsbEndpoint endpointOut = null;
    private UsbEndpoint endpointIn = null;

    // GPS related variables
    private Location location;
    private GoogleApiClient googleApiClient;
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    private LocationRequest locationRequest;
    private static final long UPDATE_INTERVAL = 5000, FASTEST_INTERVAL = 5000; // = 5 seconds

    // lists for permissions
    private ArrayList<String> permissionsToRequest;
    private ArrayList<String> permissionsRejected = new ArrayList<>();
    private ArrayList<String> permissions = new ArrayList<>();

    // integer for permission results request
    private static final int ALL_PERMISSIONS_RESULT = 1011;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // GPS
        // Check whether the GPS is on or not, If not prompt the user.
        checkGPSstate();

        // we add permissions we need to request location of the users
        permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);

        permissionsToRequest = permissionsToRequest(permissions);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (permissionsToRequest.size() > 0) {
                requestPermissions(permissionsToRequest.
                        toArray(new String[permissionsToRequest.size()]), ALL_PERMISSIONS_RESULT);
            }
        }

        // build the google api client
        googleApiClient = new GoogleApiClient.Builder(this).addApi(LocationServices.API).
                addConnectionCallbacks(this).addOnConnectionFailedListener(this).build();

        // Controls
        buttonLed = (ToggleButton)findViewById(R.id.arduinoled);
        buttonLed.setOnCheckedChangeListener(new OnCheckedChangeListener(){

            @Override
            public void onCheckedChanged(CompoundButton buttonView,
                                         boolean isChecked) {
                if(isChecked){
                    sendArduinoCommand(CMD_LED_ON);
                }else{
                    sendArduinoCommand(CMD_LED_OFF);
                }
            }});

        textOut = (EditText)findViewById(R.id.textout);
        textIn = (TextView)findViewById(R.id.textin);
        buttonSend = (Button)findViewById(R.id.send);
        buttonSendLocation = (Button)findViewById(R.id.send_location);
        buttonGetLocation = (Button)findViewById(R.id.refresh_location);

        // onClickListemer for "SEND TEXT" button.
        buttonSend.setOnClickListener(new OnClickListener(){

            @Override
            public void onClick(View v) {
                final String textToSend = textOut.getText().toString();
                if(textToSend!=""){
                    stringToRx = "";
                    textIn.setText("");
                    Thread threadsendArduinoText =
                            new Thread(new Runnable(){

                                @Override
                                public void run() {
                                    sendArduinoText(textToSend);
                                }});
                    threadsendArduinoText.start();
                }

            }});

        // onClickListemer for "SEND LOCATION DATA" button.
        buttonSendLocation.setOnClickListener(new OnClickListener(){

            @Override
            public void onClick(View v) {

                if(checkGPSstate()) {
                    final String textToSend = String.format("Lat%.2f,Lng%.2f", latitude, longitude);
                    if(textToSend!=""){
                        stringToRx = "";
                        textIn.setText("");
                        Thread threadsendArduinoLocation =
                                new Thread(new Runnable(){

                                    @Override
                                    public void run() {
                                        sendArduinoText(textToSend);
                                    }});
                        threadsendArduinoLocation.start();
                    }
                }

            }});

        // onClickListener for "GET LOCATION DATA" button.
        buttonGetLocation.setOnClickListener(new OnClickListener(){

            @Override
            public void onClick(View v) {
                if(checkGPSstate()) {
                    Toast.makeText(getApplicationContext(), "Your Location is - \nLat: " + latitude + "\nLong: " + longitude, Toast.LENGTH_LONG).show();
                }
            }});

        usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
    }

    private boolean checkGPSstate() {
        LocationManager locationService = (LocationManager) getSystemService(Context.LOCATION_SERVICE );
        boolean isGPSenabled = locationService.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if(!isGPSenabled) {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
            Toast.makeText(getApplicationContext(), "Please turn on the high accuracy for the application to work!", Toast.LENGTH_LONG).show();
        }

        return isGPSenabled;
    }

    private ArrayList<String> permissionsToRequest(ArrayList<String> wantedPermissions) {
        ArrayList<String> result = new ArrayList<>();

        for (String permission : wantedPermissions) {
            if (!hasPermission(permission)) {
                result.add(permission);
            }
        }

        return result;
    }

    private boolean hasPermission(String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (googleApiClient != null) {
            googleApiClient.connect();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!checkPlayServices()) {
            Toast.makeText(getApplicationContext(),
                    "Error! Google Play Services not installed!",
                    Toast.LENGTH_LONG).show();
        }

        Intent intent = getIntent();
        String action = intent.getAction();

        UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            setDevice(device);
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            if (deviceFound != null && deviceFound.equals(device)) {
                setDevice(null);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // stop location updates
        if (googleApiClient != null && googleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
            googleApiClient.disconnect();
        }
    }

    private boolean checkPlayServices() {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);

        if (resultCode != ConnectionResult.SUCCESS) {
            if (apiAvailability.isUserResolvableError(resultCode)) {
                apiAvailability.getErrorDialog(this, resultCode, PLAY_SERVICES_RESOLUTION_REQUEST);
            }
            else {
                finish();
            }

            return false;
        }

        return true;
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // Permissions check done! we get the last location
        location = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);

        if (location != null) {
            longitude = location.getLongitude();
            latitude = location.getLatitude();
        }

        startLocationUpdates();
    }

    private void startLocationUpdates() {
        locationRequest = new LocationRequest();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(UPDATE_INTERVAL);
        locationRequest.setFastestInterval(FASTEST_INTERVAL);

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "You need to enable permissions to display location!", Toast.LENGTH_SHORT).show();
        }

        LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            longitude = location.getLongitude();
            latitude = location.getLatitude();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case ALL_PERMISSIONS_RESULT:
                for (String permission : permissionsToRequest) {
                    if (!hasPermission(permission)) {
                        permissionsRejected.add(permission);
                    }
                }

                if (permissionsRejected.size() > 0) {
                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if(shouldShowRequestPermissionRationale(permissionsRejected.get(0))) {
                            new AlertDialog.Builder(MainActivity.this).
                                    setMessage("These permissions are mandatory to get your location!" +
                                            " Please allow them for this app to work properly.").
                                    setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialogInterface, int i) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                requestPermissions(permissionsRejected.toArray(new String[permissionsRejected.size()]),
                                                        ALL_PERMISSIONS_RESULT);
                                            }
                                        }
                                    }).
                                    setNegativeButton("Cancel", null).create().show();

                            return;
                        }
                    }
                } else {
                    if (googleApiClient != null) {
                        googleApiClient.connect();
                    }
                }

                break;

            default:
                break;
        }
    }

//    @Override
//    public void onStatusChanged(String s, int i, Bundle bundle) {
//
//    }
//
//    @Override
//    public void onProviderEnabled(String s) {
//
//    }
//
//    @Override
//    public void onProviderDisabled(String s) {
//
//    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    private void setDevice(UsbDevice device) {
        usbInterfaceFound = null;
        endpointOut = null;
        endpointIn = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbif = device.getInterface(i);

            UsbEndpoint tOut = null;
            UsbEndpoint tIn = null;

            int tEndpointCnt = usbif.getEndpointCount();
            if (tEndpointCnt >= 2) {
                for (int j = 0; j < tEndpointCnt; j++) {
                    if (usbif.getEndpoint(j).getType()
                            == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (usbif.getEndpoint(j).getDirection()
                                == UsbConstants.USB_DIR_OUT) {
                            tOut = usbif.getEndpoint(j);
                        } else if (usbif.getEndpoint(j).getDirection()
                                == UsbConstants.USB_DIR_IN) {
                            tIn = usbif.getEndpoint(j);
                        }
                    }
                }

                if (tOut != null && tIn != null) {
                    // This interface have both USB_DIR_OUT
                    // and USB_DIR_IN of USB_ENDPOINT_XFER_BULK
                    usbInterfaceFound = usbif;
                    endpointOut = tOut;
                    endpointIn = tIn;
                }
            }
        }

        if (usbInterfaceFound == null) {
            return;
        }

        deviceFound = device;

        if (device != null) {
            UsbDeviceConnection connection =
                    usbManager.openDevice(device);
            if (connection != null &&
                    connection.claimInterface(usbInterfaceFound, true)) {

                connection.controlTransfer(0x21, 34, 0, 0, null, 0, 0);
                connection.controlTransfer(0x21, 32, 0, 0,
                        new byte[] { (byte) 0x80, 0x25, 0x00,
                                0x00, 0x00, 0x00, 0x08 },
                        7, 0);

                usbDeviceConnection = connection;
                Thread thread = new Thread(this);
                thread.start();

            } else {
                usbDeviceConnection = null;
            }
        }
    }

    private void sendArduinoCommand(int control) {
        synchronized (this) {

            if (usbDeviceConnection != null) {
                byte[] message = new byte[2];
                message[0] = SYNC_WORD;
                message[1] = (byte)control;

                usbDeviceConnection.bulkTransfer(endpointOut,
                        message, message.length, 0);

                Log.d(TAG, "sendArduinoCommand: " + String.valueOf(control));
            }
        }
    }

    private void sendArduinoText(String s) {
        synchronized (this) {

            if (usbDeviceConnection != null) {

                Log.d(TAG, "sendArduinoText: " + s);

                int length = s.length();
                if(length>MAX_TEXT_LENGTH){
                    length = MAX_TEXT_LENGTH;
                }
                byte[] message = new byte[length + 3];
                message[0] = SYNC_WORD;
                message[1] = (byte)CMD_TEXT;
                message[2] = (byte)length;
                s.getBytes(0, length, message, 3);

                /*
                usbDeviceConnection.bulkTransfer(endpointOut,
                  message, message.length, 0);
                */

                byte[] b = new byte[1];
                for(int i=0; i< length+3; i++){
                    b[0] = message[i];
                    Log.d(TAG, "sendArduinoTextb[0]: " + b[0]);
                    usbDeviceConnection.bulkTransfer(endpointOut,
                            b, 1, 0);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    @Override
    public void run() {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        UsbRequest request = new UsbRequest();
        request.initialize(usbDeviceConnection, endpointIn);
        while (true) {
            request.queue(buffer, 1);
            if (usbDeviceConnection.requestWait() == request) {
                byte dataRx = buffer.get(0);
                Log.d(TAG, "dataRx: " + dataRx);
                if(dataRx!=IGNORE_00){

                    stringToRx += (char)dataRx;
                    runOnUiThread(new Runnable(){

                        @Override
                        public void run() {
                            textIn.setText(stringToRx);
                        }});
                }
            } else {
                break;
            }
        }

    }
}