package com.android.sensordatareceiver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class App_Server extends Activity{
	boolean mDualPane;
	private int key_pressed = 0;
	private int screen_paused = 0;
	private  static Handler handler ;
	public String mac;
	public String temperature ;
	public String temperatureRecvTime ;
	public String pressure;
	public String pressureRecvTime ;
	public String bloodPressure;
	public String bloodPressureRecvTime ;


	public String voltage;
	public String current;
	public String power;
	public String frequency ;
	public String energy;
	public String power_factor;
	public String power_recv_time ;
	private static final int TEMPERATURETYPE = 1;
	private static final int PRESSURETYPE = 2;
	private static final int BLOODPRESSURETYPE = 3;
	private static final int SUMMARY = 4;
	public  InetAddress local = null;
	public InetAddress server_ip = null;
	public  int server_port = 32000;

	public class NioServer implements Runnable
	{
		// The host:port combination to listen on
		private InetAddress hostAddress;
		private int port;

		// The channel on which we'll accept connections
		private ServerSocketChannel serverChannel;

		// The selector we'll be monitoring
		private Selector selector;

		// The buffer into which we'll read data when it's available
		private ByteBuffer readBuffer = ByteBuffer.allocate(8192);
		private EchoWorker worker;
		public App_Server server;

		// A list of PendingChange instances
		private List pendingChanges = new LinkedList();

		// Maps a SocketChannel to a list of ByteBuffer instances
		private Map pendingData = new HashMap();

		public NioServer(InetAddress hostAddress, int port, EchoWorker worker,App_Server server) throws IOException
		{
			this.hostAddress = hostAddress;
			this.port = port;
			this.selector = this.initSelector();
			this.worker = worker;
			this.server = server;
		}

		public void send(SocketChannel socket, byte[] data)
		{
			synchronized (this.pendingChanges)
			{
				// Indicate we want the interest ops set changed
				this.pendingChanges.add(new ChangeRequest(socket, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));

				// And queue the data we want written
				synchronized (this.pendingData)
				{
					List queue = (List) this.pendingData.get(socket);
					if (queue == null)
					{
						queue = new ArrayList();
						this.pendingData.put(socket, queue);
					}
					queue.add(ByteBuffer.wrap(data));
				}
			}
			// Finally, wake up our selecting thread so it can make the required changes
			this.selector.wakeup();
		}

		public void run()
		{
			while (true)
			{
				try
				{
					// Process any pending changes
					synchronized (this.pendingChanges)
					{
						Iterator changes = this.pendingChanges.iterator();
						while (changes.hasNext())
						{
							ChangeRequest change = (ChangeRequest) changes.next();
							switch (change.type)
							{
								case ChangeRequest.CHANGEOPS:
									SelectionKey key = change.socket.keyFor(this.selector);
									key.interestOps(change.ops);
							}
						}
						this.pendingChanges.clear();
					}

					// Wait for an event one of the registered channels
					this.selector.select();

					// Iterate over the set of keys for which events are available
					Iterator selectedKeys = this.selector.selectedKeys().iterator();
					while (selectedKeys.hasNext())
					{
						SelectionKey key = (SelectionKey) selectedKeys.next();
						selectedKeys.remove();
						if (!key.isValid())
						{
							continue;
						}

						// Check what event is available and deal with it
						if (key.isAcceptable())
						{
							this.accept(key);
						}
						else if (key.isReadable())
						{
							this.read(key);
						}
						else if (key.isWritable())
						{
							this.write(key);
						}
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
			}
		}

		private void accept(SelectionKey key) throws IOException
		{
			// For an accept to be pending the channel must be a server socket channel.
			ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();

			// Accept the connection and make it non-blocking
			SocketChannel socketChannel = serverSocketChannel.accept();
			Socket socket = socketChannel.socket();
			System.out.println("New Client Connected :"+socket.getInetAddress());
			// Toast.makeText(getApplicationContext(),"New client Connected",Toast.LENGTH_SHORT ).show();
			socketChannel.configureBlocking(false);

			// Register the new SocketChannel with our Selector, indicating
			// we'd like to be notified when there's data waiting to be read
			socketChannel.register(this.selector, SelectionKey.OP_READ);
		}

		private void read(SelectionKey key) throws IOException
		{
			SocketChannel socketChannel = (SocketChannel) key.channel();

			// Clear out our read buffer so it's ready for new data
			this.readBuffer.clear();

			// Attempt to read off the channel
			int numRead;
			try
			{
				numRead = socketChannel.read(this.readBuffer);
			}
			catch (IOException e)
			{
				// The remote forcibly closed the connection, cancel
				// the selection key and close the channel.
				key.cancel();
				socketChannel.close();
				return;
			}

			if (numRead == -1)
			{
				// Remote entity shut the socket down cleanly. Do the
				// same from our end and cancel the channel.
				System.out.println("Client Disconnected: "+ socketChannel.socket().getInetAddress());
				key.channel().close();
				key.cancel();
				return;
			}

			// Hand the data off to our worker thread
			this.worker.processData(this, socketChannel, this.readBuffer.array(), numRead);
		}

		private void write(SelectionKey key) throws IOException
		{
			SocketChannel socketChannel = (SocketChannel) key.channel();

			synchronized (this.pendingData)
			{
				List queue = (List) this.pendingData.get(socketChannel);

				// Write until there's not more data ...
				while (!queue.isEmpty())
				{
					ByteBuffer buf = (ByteBuffer) queue.get(0);
					socketChannel.write(buf);
					if (buf.remaining() > 0)
					{
						// ... or the socket's buffer fills up
						break;
					}
					queue.remove(0);
				}

				if (queue.isEmpty())
				{
					// We wrote away all data, so we're no longer interested
					// in writing on this socket. Switch back to waiting for data.
					key.interestOps(SelectionKey.OP_READ);
				}
			}
		}

		private Selector initSelector() throws IOException
		{
			// Create a new selector
			Selector socketSelector = SelectorProvider.provider().openSelector();

			// Create a new non-blocking server socket channel
			this.serverChannel = ServerSocketChannel.open();
			serverChannel.configureBlocking(false);

			// Bind the server socket to the specified address and port
			InetSocketAddress isa = new InetSocketAddress(this.hostAddress, this.port);
			try
			{
				serverChannel.socket().bind(isa);
			}
			catch(Exception e)
			{
				//  try {
				//	Thread.sleep(3000);
				//} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				//e1.printStackTrace();
				//}
				handler.sendEmptyMessage(0);
				System.out.println("exception "+ e);
			}
			System.out.println("socket created succesfully");

			// Register the server socket channel, indicating an interest in
			// accepting new connections
			serverChannel.register(socketSelector, SelectionKey.OP_ACCEPT);

			return socketSelector;
		}
	}

	public class EchoWorker implements Runnable
	{
		private List queue = new LinkedList();

		public void processData(NioServer server, SocketChannel socket, byte[] data, int count)
		{
			byte[] dataCopy = new byte[count];
			System.arraycopy(data, 0, dataCopy, 0, count);
			synchronized(queue)
			{
				queue.add(new ServerDataEvent(server, socket, dataCopy));
				queue.notify();
			}
		}

		public void run()
		{
			ServerDataEvent dataEvent;
			int app_type;

			String reg_res_msg ;
			String dereg_res_msg;
			while(true)
			{
				// Wait for data to become available
				synchronized(queue)
				{
					while(queue.isEmpty())
					{
						try
						{
							queue.wait();
						}
						catch (InterruptedException e)
						{
						}
					}
					dataEvent = (ServerDataEvent) queue.remove(0);
				}

				// Return to sender
				String value = new String(dataEvent.data);
				System.out.println("DATA RECEIVED: "+value);
				String str_data[] = value.split(",");
				if (str_data[0].equals("APP_DATA_F"))
				{
					app_type = Integer.parseInt(str_data[1]);
					if(app_type == TEMPERATURETYPE && str_data.length == 4)
					{
						dataEvent.server.server.temperature= str_data[2];
						dataEvent.server.server.temperatureRecvTime = str_data[3];
						handler.sendEmptyMessage(TEMPERATURETYPE);
					}
					else if(app_type == PRESSURETYPE && str_data.length == 4)
					{
						dataEvent.server.server.pressure= str_data[2];
						dataEvent.server.server.pressureRecvTime = str_data[3];
						handler.sendEmptyMessage(PRESSURETYPE);
					}
					else if(app_type == BLOODPRESSURETYPE && str_data.length == 4)
					{
						dataEvent.server.server.bloodPressure= str_data[2];
						dataEvent.server.server.bloodPressureRecvTime = str_data[3];
						handler.sendEmptyMessage(BLOODPRESSURETYPE);
					}
				}
			}
		}
	}

	class ServerDataEvent
	{
		public NioServer server;
		public SocketChannel socket;
		public byte[] data;

		public ServerDataEvent(NioServer server, SocketChannel socket, byte[] data)
		{
			this.server = server;
			this.socket = socket;
			this.data = data;
		}
	}

	public class ChangeRequest
	{
		public static final int REGISTER = 1;
		public static final int CHANGEOPS = 2;
		public SocketChannel socket;
		public int type;
		public int ops;

		public ChangeRequest(SocketChannel socket, int type, int ops)
		{
			this.socket = socket;
			this.type = type;
			this.ops = ops;
		}
	}

	public class Appliances
	{
		public String mac ;
		public int assoc_id;
		public float last_value;
		public Appliances (String mac,int assoc_id,float last_value)
		{
			this.assoc_id = assoc_id;
			this.mac = mac ;
		}
	}
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		System.out.println("oncreate called");

		setContentView(R.layout.fragment_layout);

		//final KeyguardManager myKM = (KeyguardManager) context .getSystemService(Context.KEYGUARD_SERVICE);
		handler = new Handler() {
			@Override
			public void handleMessage(Message ms)
			{
				if(screen_paused == 0) {

					switch(ms.what)
					{
						case 0:
							System.out.println("bind exception");
							stop_app(null);
							break;
						case TEMPERATURETYPE :
							if(key_pressed == TEMPERATURETYPE )
							{
								displayTemperature(null);
							}
							else if(key_pressed == SUMMARY)
							{
								summary(null);
							}
							break;
						case PRESSURETYPE:
							if(key_pressed == PRESSURETYPE)
							{
								displayPulse(null);
							}
							else if(key_pressed == SUMMARY)
							{
								summary(null);
							}
							break;
						case BLOODPRESSURETYPE:
							if(key_pressed == BLOODPRESSURETYPE)
							{
								displayBloodPressure(null);
							}
							else if(key_pressed == SUMMARY)
							{
								summary(null);
							}
							break;
					}
				}
			}

		};

		try
		{
			EchoWorker worker = new EchoWorker();
			new Thread(worker).start();
			new Thread(new NioServer(null,7000,worker,this)).start();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	@Override
	protected void onPause()
	{
		super.onPause();
		// If the screen is off then the device has been locked
		PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		boolean isScreenOn = powerManager.isScreenOn();

		if (!isScreenOn) {

			// The screen has been locked
			// do stuff...
			System.out.println("screen locked");
			screen_paused = 1;
		}
	}

	@Override
	protected void onResume()
	{
		super.onResume();

		// If the screen is off then the device has been locked
		PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		boolean isScreenOn = powerManager.isScreenOn();

		if (isScreenOn) {
			// The screen has been unlocked
			// do stuff...
			System.out.println("screen unlocked");
			screen_paused = 0;
			switch(key_pressed)
			{
				case TEMPERATURETYPE :
					displayTemperature(null);
					break;
				case PRESSURETYPE:
					displayPulse(null);
					break;
				case BLOODPRESSURETYPE:
					displayBloodPressure(null);
					break;
				case SUMMARY:
					summary(null);
					break;
			}
		}
	}


	public void displayTemperature(View view)
	{
		View detailsFrame = findViewById(R.id.details);
		Button temp = (Button) findViewById(R.id.temperature);
		Button light = (Button) findViewById(R.id.pulse);
		Button resistance = (Button) findViewById(R.id.bloodPressure);
		Button summary = (Button) findViewById(R.id.summary);
		temp.setBackgroundColor(Color.parseColor("#2185B0"));
		light.setBackgroundColor(Color.parseColor("#000000"));
		resistance.setBackgroundColor(Color.parseColor("#000000"));
		summary.setBackgroundColor(Color.parseColor("#000000"));
		mDualPane = detailsFrame != null && detailsFrame.getVisibility() == View.VISIBLE;
		if (mDualPane)
		{
			DetailsFragment details = (DetailsFragment) getFragmentManager().findFragmentById(R.id.details);
			// Make new fragment to show this selection.
			details = DetailsFragment.newInstance(1,this.temperature,temperatureRecvTime);
			// Execute a transaction, replacing any existing fragment with this one inside the frame.
			FragmentTransaction ft = getFragmentManager().beginTransaction();
			ft.replace(R.id.details, details);
			ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
			ft.commit();
		}
		this.key_pressed = TEMPERATURETYPE;
	}

	public void stop_app(View view)
	{
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle("Port Already in use")
				.setMessage("Please Reopen the Cluster app ")
				.setCancelable(false)
				.setNegativeButton("Close",new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						android.os.Process.killProcess(android.os.Process.myPid());

					}
				});
		AlertDialog alert = builder.create();
		alert.show();
	}

	public void displayPulse(View view)
	{
		View detailsFrame = findViewById(R.id.details);
		Button temp = (Button) findViewById(R.id.temperature);
		Button pressure = (Button) findViewById(R.id.pulse);
		Button bloodPressure = (Button) findViewById(R.id.bloodPressure);
		Button summary = (Button) findViewById(R.id.summary);
		temp.setBackgroundColor(Color.parseColor("#000000"));
		pressure.setBackgroundColor(Color.parseColor("#2185B0"));
		bloodPressure.setBackgroundColor(Color.parseColor("#000000"));
		summary.setBackgroundColor(Color.parseColor("#000000"));
		mDualPane = detailsFrame != null && detailsFrame.getVisibility() == View.VISIBLE;
		if (mDualPane)
		{
			DetailsFragment details = (DetailsFragment) getFragmentManager().findFragmentById(R.id.details);
			// Make new fragment to show this selection.
			details = DetailsFragment.newInstance(2,this.pressure,pressureRecvTime);
			// Execute a transaction, replacing any existing fragment with this one inside the frame.
			FragmentTransaction ft = getFragmentManager().beginTransaction();
			ft.replace(R.id.details, details);
			ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
			ft.commit();
		}
		this.key_pressed = PRESSURETYPE;
	}

	public void displayBloodPressure(View view)
	{
		View detailsFrame = findViewById(R.id.details);
		Button temp = (Button) findViewById(R.id.temperature);
		Button pressure = (Button) findViewById(R.id.pulse);
		Button bloodPressure = (Button) findViewById(R.id.bloodPressure);
		Button summary = (Button) findViewById(R.id.summary);
		temp.setBackgroundColor(Color.parseColor("#000000"));
		pressure.setBackgroundColor(Color.parseColor("#000000"));
		bloodPressure.setBackgroundColor(Color.parseColor("#2185B0"));
		summary.setBackgroundColor(Color.parseColor("#000000"));
		mDualPane = detailsFrame != null && detailsFrame.getVisibility() == View.VISIBLE;
		if (mDualPane)
		{
			DetailsFragment details = (DetailsFragment) getFragmentManager().findFragmentById(R.id.details);
			// Make new fragment to show this selection.
			details = DetailsFragment.newInstance(3,this.bloodPressure,bloodPressureRecvTime);
			// Execute a transaction, replacing any existing fragment with this one inside the frame.
			FragmentTransaction ft = getFragmentManager().beginTransaction();
			ft.replace(R.id.details, details);
			ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
			ft.commit();
		}
		this.key_pressed = BLOODPRESSURETYPE;
	}

	public void summary(View view)
	{
		String value = temperature + "," + temperatureRecvTime + ","+ pressure +"," + pressureRecvTime + "," +bloodPressure +","+
				bloodPressureRecvTime ;
		System.out.println("value"+value);
		View detailsFrame = findViewById(R.id.details);
		Button temp = (Button) findViewById(R.id.temperature);
		Button pressure = (Button) findViewById(R.id.pulse);
		Button bloodPressure = (Button) findViewById(R.id.bloodPressure);
		Button summary = (Button) findViewById(R.id.summary);
		temp.setBackgroundColor(Color.parseColor("#000000"));
		pressure.setBackgroundColor(Color.parseColor("#000000"));
		bloodPressure.setBackgroundColor(Color.parseColor("#000000"));
		summary.setBackgroundColor(Color.parseColor("#2185B0"));
		mDualPane = detailsFrame != null && detailsFrame.getVisibility() == View.VISIBLE;
		if (mDualPane)
		{
			DetailsFragment details = (DetailsFragment) getFragmentManager().findFragmentById(R.id.details);
			// Make new fragment to show this selection.
			details = DetailsFragment.newInstance(4,value,power_recv_time);
			// Execute a transaction, replacing any existing fragment with this one inside the frame.
			FragmentTransaction ft = getFragmentManager().beginTransaction();
			ft.replace(R.id.details, details);
			ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
			ft.commit();
		}
		this.key_pressed = SUMMARY;

	}

	public void exit(View view)
	{
		finish();
		android.os.Process.killProcess(android.os.Process.myPid());
	}

	public void onBackPressed()
	{
		finish();
		android.os.Process.killProcess(android.os.Process.myPid());
	}
}
