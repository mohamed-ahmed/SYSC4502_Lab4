import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.Timer;

public class Client{
	JFrame f = new JFrame("Client");
	JButton setupButton = new JButton("Setup");
	JButton playButton = new JButton("Play");
	JButton pauseButton = new JButton("Pause");
	JButton tearButton = new JButton("Teardown");
	JPanel mainPanel = new JPanel();
	JPanel buttonPanel = new JPanel();
	JLabel iconLabel = new JLabel();
	ImageIcon icon;
	DatagramPacket rcvdp; //UDP packet received from the server
	DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
	public int RTP_RCV_PORT; //port where the client will receive the RTP packets

	Timer timer; //timer used to receive data from the UDP socket
	byte[] buf; //buffer used to store data received from the server
	final static int INIT = 0;
	final static int READY = 1;
	final static int PLAYING = 2;
	static int state; //RTSP state == INIT or READY or PLAYING
	Socket RTSPsocket; //socket used to send/receive RTSP messages
	public BufferedReader RTSPBufferedReader;
	public BufferedWriter RTSPBufferedWriter;
	public String VideoFileName; //video file to request to the server
	public int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session
	public int RTSPid = 0; //ID of the RTSP session (given by the RTSP Server)

	final static String CRLF = "\r\n";
	static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video
	public Client() {
		f.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		});
		buttonPanel.setLayout(new GridLayout(1,0));
		buttonPanel.add(setupButton);
		buttonPanel.add(playButton);
		buttonPanel.add(pauseButton);
		buttonPanel.add(tearButton);
		setupButton.addActionListener(new setupButtonListener());
		playButton.addActionListener(new playButtonListener());
		pauseButton.addActionListener(new pauseButtonListener());
		tearButton.addActionListener(new tearButtonListener());
		iconLabel.setIcon(null);
		mainPanel.setLayout(null);
		mainPanel.add(iconLabel);
		mainPanel.add(buttonPanel);
		iconLabel.setBounds(0,0,380,280);
		buttonPanel.setBounds(0,280,380,50);

		f.getContentPane().add(mainPanel, BorderLayout.CENTER);
		f.setSize(new Dimension(390,370));
		f.setVisible(true);
		timer = new Timer(20, new timerListener());
		timer.setInitialDelay(0);
		timer.setCoalesce(true);
		buf = new byte[15000];
	}
	public static void main(String argv[]) throws Exception
	{
		Client theClient = new Client();
		int RTSP_server_port = Integer.parseInt(argv[1]);
		String ServerHost = argv[0];
		InetAddress ServerIPAddr = InetAddress.getByName(ServerHost);
		theClient.VideoFileName = argv[2];

		theClient.RTP_RCV_PORT = Integer.parseInt(argv[3]);
		theClient.RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);
		theClient.RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()) );
		theClient.RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()) );
		state = INIT;

		System.out.println("Init Success");
	}

	class setupButtonListener implements ActionListener{
		public void actionPerformed(ActionEvent e){

			System.out.println("Setup Button pressed !");

			if (state == INIT)
			{
				try{
					RTPsocket = new DatagramSocket(RTP_RCV_PORT);
					RTPsocket.setSoTimeout(5);

				}
				catch (SocketException se)
				{
					System.out.println("Socket exception: "+se);
					System.exit(0);
				}
				RTSPSeqNb = 1;
				send_RTSP_request("SETUP");
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else
				{
					state = READY;
					System.out.println("New RTSP state: READY");
				}
			}//else if state != INIT then do nothing
		}
	}
	class playButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e){

			System.out.println("Play Button pressed !");

			if (state == READY)
			{
				RTSPSeqNb++;
				send_RTSP_request("PLAY");
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else
				{
					state=PLAYING;
					System.out.println("New RTSP state: PLAYING");
					timer.start();
				}
			}//else if state != READY then do nothing
		}
	}
	class pauseButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e){

			System.out.println("Pause Button pressed !");

			if (state == PLAYING)
			{
				RTSPSeqNb++;
				send_RTSP_request("PAUSE");
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else
				{
					state=READY;
					System.out.println("New RTSP state: READY");
					timer.stop();
				}
			}//else if state != PLAYING then do nothing
		}
	}
	class tearButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e){

			System.out.println("Teardown Button pressed !");
			RTSPSeqNb++;
			send_RTSP_request("TEARDOWN");
			if (parse_server_response() != 200)
				System.out.println("Invalid Server Response");
			else
			{
				state=INIT;
				System.out.println("New RTSP state: INIT");
				timer.stop();
				System.exit(0);
			}
		}
	}

	class timerListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			rcvdp = new DatagramPacket(buf, buf.length);

			try{
				RTPsocket.receive(rcvdp);
				RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
				System.out.println("Got RTP packet with SeqNum # "+rtp_packet.getsequencenumber()+" TimeStamp "+rtp_packet.gettimestamp()+" ms, of type "+rtp_packet.getpayloadtype());
				rtp_packet.printheader();
				int payload_length = rtp_packet.getpayload_length();
				byte [] payload = new byte[payload_length];
				rtp_packet.getpayload(payload);
				Toolkit toolkit = Toolkit.getDefaultToolkit();
				Image image = toolkit.createImage(payload, 0, payload_length);
				icon = new ImageIcon(image);
				iconLabel.setIcon(icon);
			}
			catch (InterruptedIOException iioe){
				System.out.println("Nothing to read");
			}
			catch (IOException ioe) {
				System.out.println("Exception caught: "+ioe);
			}
		}
	}
	private int parse_server_response()
	{
		int reply_code = 0;

		try{
			String StatusLine = RTSPBufferedReader.readLine();
			System.out.println("RTSP Client - Received from Server:");
			System.out.println(StatusLine);

			StringTokenizer tokens = new StringTokenizer(StatusLine);
			tokens.nextToken(); //skip over the RTSP version
			reply_code = Integer.parseInt(tokens.nextToken());
			if (reply_code == 200)
			{
				String SeqNumLine = RTSPBufferedReader.readLine();
				System.out.println(SeqNumLine);

				String SessionLine = RTSPBufferedReader.readLine();
				System.out.println(SessionLine);
				tokens = new StringTokenizer(SessionLine);
				tokens.nextToken(); //skip over the Session:
				RTSPid = Integer.parseInt(tokens.nextToken());
			}
		}
		catch(Exception ex)
		{
			System.out.println("Exception caught: "+ex);
			System.exit(0);
		}

		return(reply_code);
	}
	private void send_RTSP_request(String request_type)
	{
		try{
			RTSPBufferedWriter.write(request_type+" "+VideoFileName+" "+"RTSP/1.0"+CRLF);
			RTSPBufferedWriter.write("CSeq: "+RTSPSeqNb+CRLF);
			if(request_type.equals("SETUP")) {
				RTSPBufferedWriter.write("Transport: RTP/UDP; client_port= "+RTP_RCV_PORT+CRLF);
			}
			else {
				RTSPBufferedWriter.write("Session: "+RTSPid+CRLF);
			}

			RTSPBufferedWriter.flush();
		}
		catch(Exception ex)
		{
			System.out.println("Exception caught: "+ex);
		}
	}

}//end of Class Client

