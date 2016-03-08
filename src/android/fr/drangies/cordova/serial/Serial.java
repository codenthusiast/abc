package fr.drangies.cordova.serial;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.ByteBuffer;
import java.util.List;
import android.os.Handler;
import android.os.Message;
import android.os.Bundle;
import android.os.SystemClock;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.util.Base64;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
//import com.hoho.android.usbserial.driver.FtdiSerialDriver;

import com.hoho.android.usbserial.util.HexDump;

import com.futronictech.Scanner;
import com.futronictech.UsbDeviceDataExchangeImpl;

import com.hoho.android.usbserial.util.SerialInputOutputManager;


/**
 * Cordova plugin to communicate with the android serial port
 * @author Xavier Seignard <xavier.seignard@gmail.com>
 */
public class Serial extends CordovaPlugin {
    // logging tag
    private final String TAG = Serial.class.getSimpleName();
    // actions definitions
    private static final String ACTION_REQUEST_PERMISSION = "requestPermission";
    private static final String ACTION_OPEN = "openSerial";
    private static final String ACTION_READ = "readSerial";
    private static final String ACTION_WRITE = "writeSerial";
    private static final String ACTION_WRITE_HEX = "writeSerialHex";
    private static final String ACTION_CLOSE = "closeSerial";
    private static final String ACTION_READ_CALLBACK = "registerReadCallback";

    // UsbManager instance to deal with permission and opening
    private UsbManager manager;
    // The current driver that handle the serial port
    private UsbSerialDriver driver;
    // The serial port that will be used in this plugin
    private UsbSerialPort port;
    // Read buffer, and read params
    private static final int READ_WAIT_MILLIS = 500; //200
    private static final int BUFSIZ = 4096; //1228800
    private final ByteBuffer mReadBuffer = ByteBuffer.allocate(BUFSIZ);
    public static byte[] mImageFP = null;
    // Connection info
    private int baudRate;
    private int dataBits;
    private int stopBits;
    private int parity;
    private boolean setDTR;
    
    // callback that will be used to send back data to the cordova app
    private CallbackContext readCallback;
    
    // I/O manager to handle new incoming serial data
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private SerialInputOutputManager mSerialIoManager;
    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {
                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                }
                @Override
                public void onNewData(final byte[] data) {
                    Serial.this.updateReceivedData(data);
                }
            };

    /**
     * Overridden execute method
     * @param action the string representation of the action to execute
     * @param args
     * @param callbackContext the cordova {@link CallbackContext}
     * @return true if the action exists, false otherwise
     * @throws JSONException if the args parsing fails
     */
    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "Action: " + action);
        JSONObject arg_object = args.optJSONObject(0);
        // request permission
        if (ACTION_REQUEST_PERMISSION.equals(action)) {
            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
            requestPermission(opts, callbackContext);
            return true;
        }
        // open serial port
        else if (ACTION_OPEN.equals(action)) {
            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
            openSerial(opts, callbackContext);
            return true;
        }
        // write to the serial port
        else if (ACTION_WRITE.equals(action)) {
            String data = arg_object.getString("data");
            writeSerial(data, callbackContext);
            return true;
        }
        // write hex to the serial port
        else if (ACTION_WRITE_HEX.equals(action)) {
            String data = arg_object.getString("data");
            writeSerialHex(data, callbackContext);
            return true;
        }
        // read on the serial port
        else if (ACTION_READ.equals(action)) {
            readSerial(callbackContext);
            return true;
        }
        // close the serial port
        else if (ACTION_CLOSE.equals(action)) {
            closeSerial(callbackContext);
            return true;
        }
        // Register read callback
        else if (ACTION_READ_CALLBACK.equals(action)) {
            registerReadCallback(callbackContext);
            return true;
        }
        // the action doesn't exist
        return false;
    }

    /**
     * Request permission the the user for the app to use the USB/serial port
     * @param callbackContext the cordova {@link CallbackContext}
     */
    private void requestPermission(final JSONObject opts, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                // get UsbManager from Android
                manager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
                UsbSerialProber prober;

                if (opts.has("vid") && opts.has("pid")) {
                    ProbeTable customTable = new ProbeTable();
                    Object o_vid = opts.opt("vid"); //can be an integer Number or a hex String
                    Object o_pid = opts.opt("pid"); //can be an integer Number or a hex String
                    int vid = o_vid instanceof Number ? ((Number) o_vid).intValue() : Integer.parseInt((String) o_vid,16);
                    int pid = o_pid instanceof Number ? ((Number) o_pid).intValue() : Integer.parseInt((String) o_pid,16);
                    customTable.addProduct(vid, pid, CdcAcmSerialDriver.class); //vid and pid are now integers

                    //replaced cdc drivers with ftdi

                    prober = new UsbSerialProber(customTable);

                }
                else {
                    // find all available drivers from attached devices.
                    prober = UsbSerialProber.getDefaultProber();
                }

                List<UsbSerialDriver> availableDrivers = prober.findAllDrivers(manager);

                if (!availableDrivers.isEmpty()) {
                    // get the first one as there is a high chance that there is no more than one usb device attached to your android
                    driver = availableDrivers.get(0);
                    UsbDevice device = driver.getDevice();
                    // create the intent that will be used to get the permission
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(cordova.getActivity(), 0, new Intent(UsbBroadcastReceiver.USB_PERMISSION), 0);
                    // and a filter on the permission we ask
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(UsbBroadcastReceiver.USB_PERMISSION);
                    // this broadcast receiver will handle the permission results
                    UsbBroadcastReceiver usbReceiver = new UsbBroadcastReceiver(callbackContext, cordova.getActivity());
                    cordova.getActivity().registerReceiver(usbReceiver, filter);
                    // finally ask for the permission
                    manager.requestPermission(device, pendingIntent);
                }
                else {
                    // no available drivers
                    Log.d(TAG, "No device found!");
                    callbackContext.error("No device found2!");
                }
            }
        });
    }

    /**
     * Open the serial port from Cordova
     * @param opts a {@link JSONObject} containing the connection paramters
     * @param callbackContext the cordova {@link CallbackContext}
     */
    private void openSerial(final JSONObject opts, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
                if (connection != null) {
                    // get first port and open it
                    port = driver.getPorts().get(0);
                    try {
                        // get connection params or the default values
                        baudRate = opts.has("baudRate") ? opts.getInt("baudRate") : 9600;
                        dataBits = opts.has("dataBits") ? opts.getInt("dataBits") : UsbSerialPort.DATABITS_8;
                        stopBits = opts.has("stopBits") ? opts.getInt("stopBits") : UsbSerialPort.STOPBITS_1;
                        parity = opts.has("parity") ? opts.getInt("parity") : UsbSerialPort.PARITY_NONE;
                        setDTR = opts.has("dtr") && opts.getBoolean("dtr");

                        port.open(connection);
                        port.setParameters(baudRate, dataBits, stopBits, parity);
                        if (setDTR) port.setDTR(true);
                    }
                    catch (IOException  e) {
                        // deal with error
                        Log.d(TAG, e.getMessage());
                        callbackContext.error("open");
                    }
                    catch (JSONException e) {
                        // deal with error
                        Log.d(TAG, e.getMessage());
                        callbackContext.error(e.getMessage());
                    }

                    Log.d(TAG, "Serial port opened!");
                    callbackContext.success("1Serial port opened!");
                }
                else {
                    Log.d(TAG, "Cannot connect to the device!");
                    callbackContext.error("2Cannot connect to the device!");
                }
                onDeviceStateChange();
            }
        });
    }

    /**
     * Write on the serial port
     * @param data the {@link String} representation of the data to be written on the port
     * @param callbackContext the cordova {@link CallbackContext}
     */
    private void writeSerial(final String data, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                if (port == null) {
                    callbackContext.error("Writing a closed port.");
                }
                else {
                    try {
                        Log.d(TAG, data);
                        byte[] buffer = data.getBytes();
                        port.write(buffer, 1000);
                        callbackContext.success();
                    }
                    catch (IOException e) {
                        // deal with error
                        Log.d(TAG, e.getMessage());
                        callbackContext.error(e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Write hex on the serial port
     * @param data the {@link String} representation of the data to be written on the port as hexadecimal string
     *             e.g. "ff55aaeeef000233"
     * @param callbackContext the cordova {@link CallbackContext}
     */
    private void writeSerialHex(final String data, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                if (port == null) {
                    callbackContext.error("Writing a closed port.");
                }
                else {
                    try {
                        Log.d(TAG, data);
                        byte[] buffer = hexStringToByteArray(data);
                        int result = port.write(buffer, 1000);
                        callbackContext.success(result + " bytes written.");
                    }
                    catch (IOException e) {
                        // deal with error
                        Log.d(TAG, e.getMessage());
                        callbackContext.error(e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Convert a given string of hexadecimal numbers
     * into a byte[] array where every 2 hex chars get packed into
     * a single byte.
     *
     * E.g. "ffaa55" results in a 3 byte long byte array
     *
     * @param s
     * @return
     */
    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private final Handler mHandler = new Handler() {

        public void handleMessage(Message message)
        {
            
        }
    };

    private UsbDeviceDataExchangeImpl ctx;
    private Context context;
    private boolean bGetInfo;
    private Scanner devScan;
    private int flag;
    private int mask;
    private int errCode;
    private boolean bRet;
    private final ByteBuffer bu = ByteBuffer.allocate(BUFSIZ);

    /**
     * Read on the serial port
     * @param callbackContext the {@link CallbackContext}
     */
    private void readSerial(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                
                try {
                    long l1;
                    
                    if (!bGetInfo) {
                        //Log.i("FUTRONIC", "Run fp scan");
                        boolean flag1;
                        /*if (FtrScanDemoUsbHostActivity.mUsbHostMode)
                        {
                            flag1 = devScan.OpenDeviceOnInterfaceUsbHost(ctx);
                        } else
                        {
                            flag1 = devScan.OpenDevice();
                        }*/
                        Log.d(TAG, "Loop 2");
                        flag1 = devScan.OpenDeviceOnInterfaceUsbHost(ctx);
                        Log.d(TAG, "Loop 3: " + Boolean.toString(flag1));

                        if (flag1) {
                            if (devScan.GetImageSize()) {
                                int imgWidth = devScan.GetImageWidth();
                                int imgHeight = devScan.GetImaegHeight();
                                mImageFP = new byte[(imgWidth * imgHeight)];

                                Log.d(TAG, "Assigned Image: w-" + Integer.toString(imgWidth) + " h-" + Integer.toString(imgHeight));
                                bGetInfo = true;
                            }
                        }
                    }

                    flag = 0;
                    devScan.getClass();
                    devScan.getClass();
                    mask = 65;
                    if (true) { //lfd
                        int i = flag;
                        devScan.getClass();
                        flag = i | 1;
                    }
                    if (!devScan.SetOptions(mask, flag)) {
                        Log.d(TAG, "options not set");
                        mHandler.obtainMessage(1, -1, -1, devScan.GetErrorMessage()).sendToTarget();
                    }
                    l1 = SystemClock.uptimeMillis();
                    
                    Log.d(TAG, "bla bla 3.1");
                    bRet = devScan.GetImage2(4, mImageFP); //GetImage2(4, //GetFrame
                    Log.d(TAG, "bla bla 3.2: " + Boolean.toString(bRet));

                    if (bRet) {
                        Log.d(TAG, HexDump.dumpHexString(mImageFP, 0, 32));
                        Log.d(TAG, "bla bla 4");
                        PluginResult.Status status = PluginResult.Status.OK;
                        Log.d(TAG, "bla bla 5");
                        callbackContext.sendPluginResult(new PluginResult(status,mImageFP));
                    } else {
                        Log.d(TAG, "bla bla 7.1");
                        mHandler.obtainMessage(1, -1, -1, devScan.GetErrorMessage()).sendToTarget();
                        errCode = devScan.GetErrorCode();
                        int k = errCode;
                        devScan.getClass();
                        if (k != 4306) {
                            int l = errCode;
                            devScan.getClass();
                            if (l != 0x20000001)
                            {
                                int i1 = errCode;
                                devScan.getClass();
                                if (i1 != 0x20000002)
                                {
                                    /*if (FtrScanDemoUsbHostActivity.mUsbHostMode)
                                    {
                                        devScan.CloseDeviceUsbHost();
                                    } else
                                    {
                                        devScan.CloseDevice();
                                    }*/
                                    devScan.CloseDeviceUsbHost();
                                    mHandler.obtainMessage(4).sendToTarget();
                                    return;
                                }
                            }
                        }
                        Log.d(TAG, "bla bla 7");
                        //int errCode = devScan.GetErrorCode();
                        //Log.d(TAG, Integer.toString(errCode));
                        callbackContext.error("unable to read");
                        Log.d(TAG, "bla bla 8");
                    }
                    //obj = FtrScanDemoUsbHostActivity.mSyncObj;
                    
                    mHandler.obtainMessage(3).sendToTarget();
                    //FtrScanDemoUsbHostActivity.mSyncObj.wait(2000L);

                } catch (Exception ie) { //InterruptedException
                    Log.d(TAG, "exception caught");
                    ie.printStackTrace();
                }
            
              

              //-----------------------------
                /*InterruptedException interruptedexception;
                long l1;

                Log.d(TAG, "bla bla 1");
                boolean openDev = devScan.OpenDeviceCtx(ctx);
                if (openDev) {
                    Log.d(TAG, "device opened");
                } else {
                    Log.d(TAG, "cannot open device");
                }
                Log.d(TAG, "bla bla 2");

                //get byte array from library
                flag = 0;
                devScan.getClass();
                devScan.getClass();
                mask = 65;
                Log.d(TAG, "bla bla 2.1");
                int i = flag;
                devScan.getClass();
                flag = i | 1;
                
                Log.d(TAG, "bla bla 2.2");
                boolean setOpts = devScan.SetOptions(mask, flag);
                if (setOpts) {
                    Log.d(TAG, "options set");
                } else {
                    Log.d(TAG, "options not set");
                }

                Log.d(TAG, "bla bla 3");
                ByteBuffer bu = ByteBuffer.allocate(BUFSIZ);

                boolean bRet = devScan.GetFrame(bu.array()); //GetImage2(4, 
                if (bRet) {
                    Log.d(TAG, "true");
                } else {
                    Log.d(TAG, "false");
                }

                if (bRet) {
                    Log.d(TAG, "bla bla 4");
                    PluginResult.Status status = PluginResult.Status.OK;
                    final byte[] data = new byte[bu.array().length];
                    bu.get(data, 0, bu.array().length);
                    Log.d(TAG, "bla bla 5");
                    callbackContext.sendPluginResult(new PluginResult(status,data));
                    Log.d(TAG, "bla bla 6");
                } else {
                    Log.d(TAG, "bla bla 7");
                    int errCode = devScan.GetErrorCode();
                    Log.d(TAG, Integer.toString(errCode));
                    callbackContext.error("unable to read");
                    Log.d(TAG, "bla bla 8");
                }*/
                /*if (port == null) {
                    callbackContext.error("Reading a closed port.");
                } 
                else {
                    

                    /*try { 
                        Log.d(TAG, "Custom tag to check read position 1");
                        int len = port.read(mReadBuffer.array(), READ_WAIT_MILLIS);
                        Log.d(TAG, "Custom tag to check read position 2: " + len);
                        // Whatever happens, we send an "OK" result, up to the
                        // receiver to check that len > 0
                        PluginResult.Status status = PluginResult.Status.OK;
                        Log.d(TAG, "Custom tag to check read position 3");
                        if (len > 0) {
                            Log.d(TAG, "Read data len=" + len);
                            final byte[] data = new byte[len];
                            mReadBuffer.get(data, 0, len);
                            mReadBuffer.clear();
                            callbackContext.sendPluginResult(new PluginResult(status,data));
                        }
                        else {
                            final byte[] data = new byte[0];
                            callbackContext.sendPluginResult(new PluginResult(status, data));
                        }
                        Log.d(TAG, "Custom tag to check read position 4");
                    }
                    catch (IOException e) {
                        // deal with error
                        Log.d(TAG, "caught IO exception");
                        Log.d(TAG, e.getMessage());
                        callbackContext.error(e.getMessage());
                    }
                }*/
            }
        });
    }

    /**
     * Close the serial port
     * @param callbackContext the cordova {@link CallbackContext}
     */
    private void closeSerial(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    // Make sure we don't die if we try to close an non-existing port!
                    if (port != null) {
                        port.close();
                    }
                    port = null;
                    callbackContext.success();
                }
                catch (IOException e) {
                    // deal with error
                    Log.d(TAG, e.getMessage());
                    callbackContext.error(e.getMessage());
                }
                onDeviceStateChange();
            }
        });
    }


    /**
     * Stop observing serial connection
     */
    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager.");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    /**
     * Observe serial connection
     */
    private void startIoManager() {
        if (driver != null) {
            Log.i(TAG, "Starting io manager.");
            mSerialIoManager = new SerialInputOutputManager(port, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    /**
     * Restart the observation of the serial connection
     */
    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    /**
     * Dispatch read data to javascript
     * @param data the array of bytes to dispatch
     */
    private void updateReceivedData(byte[] data) {
        if( readCallback != null ) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, data);
            result.setKeepCallback(true);
            readCallback.sendPluginResult(result);
        }
    }

    /**
     * Register callback for read data
     * @param callbackContext the cordova {@link CallbackContext}
     */
    private void registerReadCallback(final CallbackContext callbackContext) {
        Log.d(TAG, "Registering callback");
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                Log.d(TAG, "Registering Read Callback");
                readCallback = callbackContext;
                JSONObject returnObj = new JSONObject();
                addProperty(returnObj, "registerReadCallback", "true");
                // Keep the callback
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, returnObj);
                pluginResult.setKeepCallback(true);
                callbackContext.sendPluginResult(pluginResult);
            }
        });
    }

    /** 
     * Paused activity handler
     * @see org.apache.cordova.CordovaPlugin#onPause(boolean)
     */
    @Override
    public void onPause(boolean multitasking) {
        stopIoManager();
        if (port != null) {
            try {
                port.close();
            } catch (IOException e) {
                // Ignore
            }
            port = null;
        }
    }

    
    /**
     * Resumed activity handler
     * @see org.apache.cordova.CordovaPlugin#onResume(boolean)
     */

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        // your init code here
        Log.d(TAG, "Activity created, context assigned");
        context = (Context)this.cordova.getActivity();

        ctx = new UsbDeviceDataExchangeImpl(context, mHandler);
        
        bGetInfo = false;
        devScan = new Scanner();
    }

    @Override
    public void onResume(boolean multitasking) {
        Log.d(TAG, "Resumed, driver=" + driver);
        if (driver == null) {
            Log.d(TAG, "No serial device to resume.");
        } 
        else {
            UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
            if (connection != null) {
                // get first port and open it
                port = driver.getPorts().get(0);
                try {
                    port.open(connection);
                    port.setParameters(baudRate, dataBits, stopBits, parity);
                    if (setDTR) port.setDTR(true);
                }
                catch (IOException  e) {
                    // deal with error
                    Log.d(TAG, e.getMessage());
                }
                Log.d(TAG, "Serial port opened!");
            }
            else {
                Log.d(TAG, "Cannot connect to the device!");
            }
            Log.d(TAG, "Serial device: " + driver.getClass().getSimpleName());
        }
        
        onDeviceStateChange();
    }


    /**
     * Destroy activity handler
     * @see org.apache.cordova.CordovaPlugin#onDestroy()
     */
    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroy, port=" + port);
        if(port != null) {
            try {
                port.close();
            }
            catch (IOException e) {
                Log.d(TAG, e.getMessage());
            }
        }
        onDeviceStateChange();
    }

    /**
     * Utility method to add some properties to a {@link JSONObject}
     * @param obj the json object where to add the new property
     * @param key property key
     * @param value value of the property
     */
    private void addProperty(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value);
        }
        catch (JSONException e){}
    }

    /**
     * Utility method to add some properties to a {@link JSONObject}
     * @param obj the json object where to add the new property
     * @param key property key
     * @param bytes the array of byte to add as value to the {@link JSONObject}
     */
    private void addPropertyBytes(JSONObject obj, String key, byte[] bytes) {
        String string = Base64.encodeToString(bytes, Base64.NO_WRAP);
        this.addProperty(obj, key, string);
    }
}