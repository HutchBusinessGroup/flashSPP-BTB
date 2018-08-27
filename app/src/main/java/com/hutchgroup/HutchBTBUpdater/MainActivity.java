package com.hutchgroup.HutchBTBUpdater;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IllegalFormatCodePointException;
import java.util.ListIterator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 1;
    private static final UUID sppUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final byte RS232_FLAG = (byte) 0xC0;
    private static final byte RS232_ESCAPE = (byte) 0xDB;
    private static final byte RS232_ESCAPE_FLAG = (byte) 0xDC;
    private static final byte RS232_ESCAPE_ESCAPE = (byte) 0xDD;
    private static final String TAG = "J1939";
    private static final byte VNA_RESULT = (byte) 252;
    private static final byte VNA_BL2_START = 100;
    private static final byte VNA_BL2_DATA = 101;
    private static final byte VNA_BL2_STOP = 102;

    private BluetoothSocket mSocket;
    private boolean connected;
    private byte[] m_buffer;
    private int m_count;
    private boolean isInvalid;
    private boolean isStuffed;
    private int m_size;
    private MenuItem connect_button;
    private TextView mDataField;
    private static volatile boolean nextLine;
    private static volatile boolean start;
    private static volatile boolean complete;
    TextView tvSummary, tvRead, tvTotalSize, tvTotalPercentage, tvBTBInfo;
    ProgressBar progressBar;
    String hardwareVersion, softwareVersion, macId;

    private void initialize() {
        Button startFlash = (Button) findViewById(R.id.sendImageButton);
        startFlash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (!connected) {
                    showMsg("BTB is not connected yet!!!");
                    return;
                }
                Button b = (Button) v;
                b.setText(R.string.flashing);
                b.setClickable(false);
                totalRead = 0;
                totalSize = 0;

                if (flashThread != null && flashThread.isAlive()) {
                    flashThread.interrupt();
                    while (flashThread.isAlive()) Thread.yield();
                } else {
                    flashThread = new Thread(flashRun);
                    flashThread.start();
                }
            }
        });
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        tvSummary = (TextView) findViewById(R.id.tvSummary);
        tvRead = (TextView) findViewById(R.id.tvRead);
        tvTotalSize = (TextView) findViewById(R.id.tvTotalSize);
        tvTotalPercentage = (TextView) findViewById(R.id.tvTotalPercentage);
        mDataField = (TextView) findViewById(R.id.data_value);
        tvBTBInfo = (TextView) findViewById(R.id.tvBTBInfo);
        connectBTB();

    }

    private void progressUpdate() {
        tvRead.setText("Read: " + totalRead + " bytes");
        tvTotalSize.setText("Total: " + totalSize + " bytes");
        int progress = (int) (totalRead * 100 / totalSize);
        tvTotalPercentage.setText(progress + " %");
        progressBar.setProgress(progress);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkAndGrantPermissions();
        initialize();
    }


    @Override
    protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        connect_button = menu.findItem(R.id.action_connect);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_connect) {
            connect_button = item;

            if (item.getTitle().toString().compareToIgnoreCase("Connect") == 0) {
                // do BT connect
                item.setTitle("Connecting...");
                connectBTB();
               /* Intent serverIntent = new Intent(this, DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);*/
            } else {
                disconnect();
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_CANCELED) {
            disconnect();
        }
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a bluetoothDevice to connect
                if (resultCode == Activity.RESULT_OK) {
                    final String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    Thread connectThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            connectDevice(address, false);
                        }
                    });
                    connectThread.start();
                }
                break;
        }
    }

    private void displayData(final String data) {
        if (data != null && mDataField != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mDataField.setText(data);

                }
            });
        }
    }


    private Runnable readRun = new Runnable() {
        public void run() {
            receiveDataFromBT(mSocket);
        }
    };
    private Thread readThread;

    private BluetoothSocket connectDevice(String address, boolean backup) {
        // Get the BluetoothDevice object
        connected = false;
        BluetoothAdapter bAdapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice bluetoothDevice;
        if (bAdapter != null) {
            bluetoothDevice = bAdapter.getRemoteDevice(address);
        } else {
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "This device does not have Bluetooth.",
                            Toast.LENGTH_SHORT).show();
                    if (connect_button != null) connect_button.setTitle("Connect");
                }
            });
            return null;
        }
        if (backup) {
            try {
                Log.d(TAG, "Reconnect with invoke");
                mSocket = invokeConnect(bluetoothDevice);
                connected = true;
            } catch (IOException ioe) {
                Log.e(TAG, "", ioe);
            } catch (Exception e) {
                Log.e(TAG, "this was a bad idea", e);
            }
        } else {
            try {
                mSocket = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(sppUUID);
                mSocket.connect();
                connected = true;
            } catch (IOException ioex) {
                Log.e(TAG, "Insecure failed, trying reflection");
                try {
                    if (mSocket != null) {
                        mSocket.close();
                    }
                } catch (IOException ioe) {
                    Log.e(TAG, "", ioe);
                }

                try {
                    mSocket = invokeConnect(bluetoothDevice);
                    connected = true;
                } catch (IOException ioe) {
                    Log.e(TAG, "invoke", ioe);
                } catch (Exception e) {
                    Log.e(TAG, "this was a bad idea", e);
                }
            }
        }
        if (connected) {

            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Connected!", Toast.LENGTH_SHORT).show();
                    if (connect_button != null) connect_button.setTitle("Disconnect");
                    Button startFlash = (Button) findViewById(R.id.sendImageButton);
                    startFlash.setText(R.string.flash);
                    startFlash.setClickable(true);
                }
            });
            m_buffer = new byte[4096];
            m_count = 0;
            if (readThread != null && readThread.isAlive()) {
                readThread.interrupt();
                while (readThread.isAlive()) Thread.yield();
            } else {
                readThread = new Thread(readRun);
                readThread.setPriority(4);
                readThread.start();
            }

        } else {
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Bluetooth connection error, try again",
                            Toast.LENGTH_SHORT).show();
                    if (connect_button != null) connect_button.setTitle("Connect");
                    disconnect();
                }
            });
        }

        return (mSocket);
    }

    private BluetoothSocket invokeConnect(BluetoothDevice bluetoothDevice)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, IOException {
        Method m = bluetoothDevice.getClass().getMethod("createRfcommSocket", int.class);
        BluetoothSocket bs = (BluetoothSocket) m.invoke(bluetoothDevice, 1);
        bs.connect();
        return bs;
    }

    private void disconnect() {
        connected = false;
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (connect_button != null) connect_button.setTitle("Connect");
                Button startFlash = (Button) findViewById(R.id.sendImageButton);
                startFlash.setClickable(false);
            }
        });


        try {
            if (readThread != null) readThread.interrupt();
            if (mSocket != null) {
                mSocket.close();
                mSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "", e);
        }
    }

    private void receiveDataFromBT(BluetoothSocket socket) {
        try {
            byte[] buffer = new byte[1024];
            int buf_len;


            if (socket == null) {
                return;
            }

            InputStream inputStream = socket.getInputStream();

            while (true) {
                try {
                    if (Thread.interrupted() && !connected) {
                        inputStream.close();
                        inputStream = null;
                        return;
                    }
                    // Read from the InputStream
                    buf_len = inputStream.read(buffer);
                    Thread.sleep(1);
                    if (buf_len == -1) {
                        inputStream.close();
                        break;
                    }
                    parseMessage(buffer, buf_len);

                } catch (IOException e) {

                    if (!connected && Thread.interrupted()) {
                        inputStream.close();
                        inputStream = null;
                        return;
                    }
                    inputStream.close();
                    inputStream = null;
                    return;
                } catch (InterruptedException e) {
                    if (!connected) {
                        inputStream.close();
                        inputStream = null;
                        Log.e(TAG, "Interrupted read", e);
                        return;
                    }
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "", e);
        }
    }

    public void parseMessage(byte[] buf, int len) {
        for (int i = 0; i < len; i++) {
            processCharFromBus(buf[i]);
        }
    }

    private void processCharFromBus(byte val) {
        try {
            //Is it the start of the message?
            if (val == RS232_FLAG) {
                isInvalid = false;
                isStuffed = false;
                m_size = -1;
                m_count = 0;
            } else if (!isInvalid) {
                if (val == RS232_ESCAPE) {
                    isStuffed = true;
                } else {
                    //If previous byte was an escape, then decode current byte
                    if (isStuffed) {
                        isStuffed = false;
                        if (val == RS232_ESCAPE_FLAG) {
                            val = RS232_FLAG;
                        } else if (val == RS232_ESCAPE_ESCAPE) {
                            val = RS232_ESCAPE;
                        } else {
                            isInvalid = true;
                            // Invalid byte after escape, must abort
                            return;
                        }
                    }
                    //At this point data is always unstuffed
                    if (m_count < m_buffer.length) {
                        m_buffer[m_count] = val;
                        m_count++;
                    } else {
                        //Full buffer
                        isInvalid = true;
                    }

                    //At 2 bytes, we have enough info to calculate a real message length
                    if (m_count == 2) {
                        m_size = ((m_buffer[0] << 8) | m_buffer[1]) + 2;
                    }

                    //Have we received the entire message? If so, is it valid?
                    if (m_count == m_size && val == cksum(m_buffer, m_count - 1)) {
                        m_count--; //Ignore the checksum at the end of the message
                        processPacket(m_buffer);
                        isInvalid = true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "processChar", e);
        }
    }

    private static final int STATS = 23;

    private void processPacket(byte[] packet) {
        // switch on vmsg
        switch (packet[2]) {
            case VNA_RESULT:
                // set flag to send next byte
                if (packet[3] == VNA_BL2_DATA) {
                    if (packet[4] == 1) {
                        nextLine = false;
                    }
                    nextLine = true;
                    synchronized (this) {
                        notifyAll();
                    }
                    break;
                } else if (packet[3] == VNA_BL2_STOP) {
                    // we're done
                    displayData("Update complete");
                    complete = true;
                    synchronized (this) {
                        notifyAll();
                    }
                    break;
                } else if (packet[3] == VNA_BL2_START) {
                    displayData("Starting update");
                    start = true;
                    synchronized (this) {
                        notifyAll();
                    }
                }
            case STATS:
                byte a = packet[11];
                byte b = packet[12];
                byte c = packet[13];
                byte d = packet[14];
                Long canFramesCount = (long) (((a & 0xFF) << 24)
                        | ((b & 0xFF) << 16) | ((c & 0xFF) << 8) | (d & 0xFF));
                hardwareVersion = (packet[15] & 0xFF) + "";
                softwareVersion = (packet[16] & 0xFF) + "";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String info = "Hardware Version: " + hardwareVersion + ", Software Version: " + softwareVersion + ", Mac: " + macId;
                        tvBTBInfo.setText(info);
                    }
                });
                break;
        }
    }

    private int cksum(byte[] data, int numbytes) {
        int count = 0;

        for (int i = 0; i < numbytes; i++) {
            count += uByte(data[i]);
        }
        return (byte) (~(count & 0xFF) + (byte) 1);
    }

    private int uByte(byte b) {
        return (int) b & 0xFF;
    }

    long totalSize = 0;

    private BufferedReader getFlashFile() {
        BufferedReader stream = null;
        try {
            String path = Environment.getExternalStorageDirectory().toString() + "/BTBFlash";
            File directory = new File(path);
            if (directory.exists()) {
                File[] files = directory.listFiles();

                if (files != null && files.length == 1) {
                    File file = files[0];
                    totalSize = file.length();
                    FileInputStream fileInputStream = new FileInputStream(file);
                    stream = new BufferedReader(new InputStreamReader(fileInputStream));
                }
            }
        } catch (Exception exe) {
            showMsg("Error: " + exe.getMessage());
        }
        return stream;
    }

    long totalRead = 0;

    private void showMsg(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    Runnable flashRun = new Runnable() {
        @Override
        public void run() {
            // open file
            Log.d(TAG, "opening file");
            try {
                // BufferedReader stream = new BufferedReader(new InputStreamReader(getAssets().open("vna2-eld-aio.v47nr.nble.x")));
                BufferedReader stream = getFlashFile();
                if (stream == null) {
                    showMsg("Flash file is not found.");
                    abortFlash();
                    return;
                }
                String str;
                Pattern p = Pattern.compile("([0-9A-F][0-9A-F])([0-9A-F][0-9A-F][0-9A-F][0-9A-F])([0-9A-F][0-9A-F])(.*)([0-9A-F][0-9A-F])");
                long baseAddr = 0;
                txStart();
                awaitStartAck();


                int[] buffered_data = new int[1024];
                int buffered_data_len = 0;
                long expected_addr = 0;
                long start_addr = 0;
                while ((str = stream.readLine()) != null) {
                    totalRead += (str + "\n").length();
                    Matcher match = p.matcher(str);
                    if (!match.find()) {
                        /* if somehow we don't match skip line */
                        continue;
                    }

                    int len = Integer.parseInt(match.group(1), 16);
                    final int offset = Integer.parseInt(match.group(2), 16);
                    int type = Integer.parseInt(match.group(3), 16);
                    String data = match.group(4);
                    int cksum = Integer.parseInt(match.group(5), 16);
                    long addr;
                    switch (type) {
                        case 0:
                            /* data record */
                            // check if in valid section
                            addr = baseAddr + offset;
                            // check contiguous
                            if (baseAddr + offset != expected_addr) {
                                // send data now because not contiguous
                                if (buffered_data_len != 0) {
                                    txData(start_addr, buffered_data, buffered_data_len);
                                }
                                // set up buffer for new data
                                buffered_data_len = 0;
                                start_addr = addr;
                            }

                            // read next data
                            for (int i = 0; i < len; i++) {
                                int data_byte = Integer.parseInt(
                                        String.valueOf(data.charAt(2 * i)) + data.charAt(2 * i + 1), 16);
                                buffered_data[buffered_data_len++] = data_byte;
                            }
                            // buffer
                            // update expected next
                            expected_addr = baseAddr + offset + len;
                            // send if max size for bootloader
                            if (buffered_data_len >= 128) {
                                txData(start_addr, buffered_data, buffered_data_len);
                                buffered_data_len = 0;
                                start_addr = expected_addr;
                            }


                            final String addrStr = Long.toHexString(start_addr);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {

                                    progressUpdate();
                                    displayData(addrStr);
                                }
                            });
                            break;
                        case 1:
                            if (cksum != 0xFF) {
                                // EOF is incorrect
                                // abort bootload
                                abortFlash();
                                break;
                                //return;
                            }
                            if (buffered_data_len != 0) {
                                txData(start_addr, buffered_data, buffered_data_len);
                            }
                            txComplete();
                            awaitCompleteAck();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    totalRead = totalSize;
                                    progressUpdate();

                                    showAlertMsg("BTB is updated successfully. Please click OK to continue");
                                    displayData("Complete!");
                                    disconnect();
                                }
                            });
                            readThread.interrupt();
                            break;
                        case 2:
                            baseAddr = Integer.parseInt(data + "00", 16);
                            break;
                        case 4:
                            /* extended linear address record */
                            if (len != 2 || offset != 0) {
                                /* should always have two data bytes */
                                abortFlash();
                                //return;
                            }
                            baseAddr = Long.parseLong(data + "0000", 16);
                            break;
                        case 5:
                            // __main function address not needed for flashing
                            break;
                        default:
                            break;
                    }
                }
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    };
    Thread flashThread;


    private void abortFlash() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Button startFlash = (Button) findViewById(R.id.sendImageButton);
                startFlash.setText(R.string.flash);
                startFlash.setClickable(true);

                displayData("Failed!");
            }
        });

    }

    private synchronized void awaitDataWriteAck() {
        while (!nextLine) {
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "await data interrupted");
            }
        }
        nextLine = false;
    }

    private synchronized void awaitCompleteAck() {
        while (!complete) {
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "interrupted waiting for complete signal.");
            }
        }
        complete = false;
    }

    private synchronized void awaitStartAck() {
        while (!start) {
            try {
                wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "interrupted waiting for complete signal.");
            }
        }
        start = false;
    }

    private void txStart() {
        byte[] txBuf = new byte[5];
        txBuf[0] = RS232_FLAG;
        txBuf[1] = 0;
        txBuf[2] = 2;
        txBuf[3] = VNA_BL2_START;
        txBuf[4] = (byte) 0x9A;
        sendCommand(new TxStruct(txBuf, 5));
    }

    private void txComplete() {
        byte[] txBuf = new byte[5];
        txBuf[0] = RS232_FLAG;
        txBuf[1] = 0;
        txBuf[2] = 2;
        txBuf[3] = VNA_BL2_STOP;
        txBuf[4] = (byte) 0x98;
        sendCommand(new TxStruct(txBuf, 5));
    }

    private void txData(final long addr, final int[] packet, int len) {
        Log.d(TAG, String.format("%04X %d", addr, len));
        buildPacket(addr, packet, len);
        awaitDataWriteAck();
    }

    private void sendCommand(TxStruct command) {
        if (mSocket != null) {
            try {
                mSocket.getOutputStream().write(command.getBuf(), 0, command.getLen());
            } catch (IOException e) {
                Log.e(TAG, "Send Command Socket Closed", e);
            }
        }
    }

    private void buildPacket(long addr, int[] packet, int len) {
        ArrayList<Byte> txBuf = new ArrayList<>(len + 9);
        int size = len + 6;
        txBuf.add(RS232_FLAG);
        txBuf.add((byte) 0);
        txBuf.add((byte) (size));
        txBuf.add(VNA_BL2_DATA);
        txBuf.add((byte) (addr >> 24));
        txBuf.add((byte) (addr >> 16));
        txBuf.add((byte) (addr >> 8));
        txBuf.add((byte) addr);
        for (int i = 0; i < len; i++) {
            txBuf.add((byte) (packet[i] & 0xFF));
        }
        txBuf.add(cksum(txBuf));

        ListIterator<Byte> iterator = txBuf.listIterator(1);
        while (iterator.hasNext()) {
            Byte element = iterator.next();
            if (element == RS232_FLAG) {
                iterator.set(RS232_ESCAPE);
                iterator.add(RS232_ESCAPE_FLAG);
            } else if (element == RS232_ESCAPE) {
                iterator.add(RS232_ESCAPE_ESCAPE);
            }
        }

        sendCommand(new TxStruct(txBuf));
    }

    private byte cksum(ArrayList<Byte> txBuf) {
        int retval = -0xC0;
        for (byte element : txBuf) {
            retval += element;
        }
        return (byte) (~((byte) retval) + 1);
    }

    private class TxStruct {
        private byte[] buf;
        private int len;

        public TxStruct() {
            buf = new byte[10];
            len = 0;
        }

        public TxStruct(int bufSize, int len) {
            buf = new byte[bufSize];
            this.len = len;
        }

        public TxStruct(byte[] buf, int len) {
            this.buf = buf;
            this.len = len;
        }

        public TxStruct(ArrayList<Byte> txBuf) {
            this.buf = new byte[txBuf.size()];
            this.len = txBuf.size();
            for (int i = 0; i < len; i++) {
                buf[i] = txBuf.get(i);
            }
        }

        public void setLen(int length) {
            len = length;
        }

        public int getLen() {
            return len;
        }

        public void setBuf(int pos, byte data) {
            if (pos >= 0 && pos < buf.length) buf[pos] = data;
        }

        public byte[] getBuf() {
            return buf;
        }
    }

    public boolean hasPermissions(String... permissions) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getApplicationContext() != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(getApplicationContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }

        return true;
    }

    public void checkAndGrantPermissions() {

        String[] PERMISSIONS = {Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.INTERNET, android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE
                , Manifest.permission.ACCESS_NETWORK_STATE
        };
        if (!hasPermissions(PERMISSIONS)) {
            ActivityCompat.requestPermissions(MainActivity.this, PERMISSIONS, 999);
        }
    }


    private AlertDialog alertDialog = null;

    public void showAlertMsg(String msg) {
        if (alertDialog != null && alertDialog.isShowing()) {
            return;
        }
        try {
            alertDialog = new AlertDialog.Builder(MainActivity.this).create();
            alertDialog.setCancelable(true);
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.setTitle("Hutch BTB Updater");
            alertDialog.setIcon(R.mipmap.ic_launcher);
            alertDialog.setMessage(msg);
            alertDialog.setButton(DialogInterface.BUTTON_NEUTRAL, "OK",
                    new DialogInterface.OnClickListener() {

                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            alertDialog.cancel();

                            Intent LaunchIntent = getPackageManager().getLaunchIntentForPackage("com.hutchgroup.e_log");
                            if (LaunchIntent != null) {
                            /*LaunchIntent.setAction(Intent.ACTION_MAIN);
                            LaunchIntent.addCategory(Intent.CATEGORY_LAUNCHER);*/
                                LaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(LaunchIntent);
                            }
                            System.exit(1);
                        }
                    });
            alertDialog.show();
            //return alertDialog;
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

    private void connectBTB() {

        Thread connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {

                            if (connect_button != null) connect_button.setTitle("Connecting...");
                        }
                    });
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    Set<BluetoothDevice> devices = adapter.getBondedDevices();
                    String deviceAddress = "", deviceName = "";
                    for (BluetoothDevice device : devices) {
                      /*  if (device.getName().startsWith("RNBT") || device.getName().startsWith("HUTCH")) {*/
                        deviceAddress = device.getAddress();
                        macId = deviceAddress;
                        deviceName = device.getName();
                        break;
                        /// }
                    }

                    if (deviceAddress.isEmpty()) {
                        showMsg("BTB device is not paired. Please pair it before continue!");
                    } else {
                        connectDevice(deviceAddress, false);
                    }
                } catch (Exception exe) {

                }
            }
        });
        connectThread.start();


    }
}
