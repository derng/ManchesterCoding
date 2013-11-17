/*
 *  (c) K.Bryson, Dept. of Computer Science, UCL (2013)
 */

package physical_network;

import java.math.BigInteger;


/**
 * 
 * %%%%%%%%%%%%%%%% YOU NEED TO IMPLEMENT THIS %%%%%%%%%%%%%%%%%%
 * 
 * Represents a network card that can be attached to a particular wire.
 * 
 * It has only two key responsibilities:
 * i) Allow the sending of data frames consisting of arrays of bytes using send() method.
 * ii) If a data frame listener is registered during construction, then any data frames
 *     received across the wire should be sent to this listener.
 *
 * @author K. Bryson
 */
public class NetworkCard extends Thread {
    
	// Wire pair that the network card is atatched to.
    private final TwistedWirePair wire;

    // Unique device name given to the network card.
    private final String deviceName;
    
    // A 'data frame listener' to call if a data frame is received.
    private final FrameListener listener;

    
    // Default values for high, low and mid- voltages on the wire.
    private final double HIGH_VOLTAGE = 2.5;
    private final double LOW_VOLTAGE = -2.5;
    
    // Default value for a signal pulse width that should be used in milliseconds.
    private final int PULSE_WIDTH = 450;
    
    // Default value for maximum payload size in bytes.
    private final int MAX_PAYLOAD_SIZE = 1500;

    private boolean clock;
    private final String SLASH = "01011100";
    private final String END_BYTE = "01111110";
    
    /**
     * NetworkCard constructor.
     * @param deviceName This provides the name of this device, i.e. "Network Card A".
     * @param wire       This is the shared wire that this network card is connected to.
     * @param listener   A data frame listener that should be informed when data frames are received.
     *                   (May be set to 'null' if network card should not respond to data frames.)
     */
    public NetworkCard(String deviceName, TwistedWirePair wire, FrameListener listener) {
    	
    	this.deviceName = deviceName;
    	this.wire = wire;
    	this.listener = listener;
    	
    }

    /**
     * Tell the network card to send this data frame across the wire.
     * NOTE - THIS METHOD ONLY RETURNS ONCE IT HAS SENT THE DATA FRAME.
     * 
     * @param frame  Data frame to send across the network.
     */
    public void send(DataFrame frame) throws InterruptedException {
    	byte[] payload = frame.getPayload();
    	sleep(PULSE_WIDTH/2);
    	
    	//Set highvalue to signal start of sending
    	this.wire.setVoltage(this.deviceName, HIGH_VOLTAGE);
    	sleep(PULSE_WIDTH/2);
    	
    	//Send all the bytes in the payload
    	for (byte mByte :payload) {
    		sendByteToBits(mByte);
    	}
    	//Send the final byte 0x7E to signal end of transmission
    	sendByteToBits((byte)92);
    	sendByteToBits((byte)126);
    	
    	this.wire.setVoltage(this.deviceName, 0);
    }
    
    /*Convert byte value to bits to send onto the wire using the Manchester Coding*/
    private void sendByteToBits(byte mByte) throws InterruptedException {
    	int bitValue = 128;
    	//Algorithm to determine the 8-bit value of the byte
    	while (bitValue != 0) {
    		boolean signal = bitValue <= mByte; // True - signal 1
    		if (signal) {
    			mByte -= bitValue;	
    		}
    		
    		//Apply XOR on signal and clock
    		clockTick();
    		setVolts(signal ^ this.clock);
    		clockTick();
    		setVolts(signal ^ this.clock);

    		bitValue /= 2;
    	}
    }
    
    /*Clock to control the signal voltages*/
    private void clockTick() throws InterruptedException {
    	this.clock = !this.clock;
    	sleep(PULSE_WIDTH/2);
    }
    
    /*Set voltage on the wire*/
    private void setVolts(boolean signal) {
    	if (signal) {
    		this.wire.setVoltage(this.deviceName, HIGH_VOLTAGE);
    		return;
    	}
    	this.wire.setVoltage(this.deviceName, LOW_VOLTAGE);
    }
    
    
	
	public void run() {
        
		if (listener != null) {
	        try {
	        	double BOUNDARY = 4*HIGH_VOLTAGE/5;
	        	double volts;
	        	
	        	while (true) {
	        		volts = this.wire.getVoltage(this.deviceName);
	        		
	        		if (volts > BOUNDARY) {
	        			//Data is being sent, sleep enough to pass the alert
	        			sleep(3*PULSE_WIDTH/4);
	        			
	        			//Create frame and display it
	        			DataFrame frame = readSignalData();
	        			this.listener.receive(frame);
	        			
	        			//Exit
	        			System.exit(1);
	        		}
	        		sleep(10);
	        	}
	        	
	        } catch (InterruptedException except) {
	            System.out.println("Network Card Interrupted: " + getName());
	        }
		}
	}

	/*Method reads the incoming Manchester coding signals on the wire and 
	 *returns a data frame of the data obtained from it*/
	private DataFrame readSignalData() throws InterruptedException {
		double volts = this.wire.getVoltage(this.deviceName);
		double currentVolts;
		String byteStr = "";
		String hexBytes = "";
		boolean flag;
		boolean specialChar = false;

		//Stop reading when max payload size reached
		while (hexBytes.length()/2 < MAX_PAYLOAD_SIZE) {
			currentVolts = this.wire.getVoltage(this.deviceName);
			flag = false;
			byteStr = refreshByte(byteStr);
			
			//Conditions to detect signal transitions
			if (volts < 0) {
				if (currentVolts >= 0) {
					byteStr += '1';
					flag = clockShift(volts);
				}
			} else if (volts > 0) {
				if (currentVolts <= 0) {
					byteStr += '0';
					flag = clockShift(volts);
				}
			}

			//System.out.println("b: "+byteStr);
			if (!specialChar) {
				if (byteStr.equals(SLASH)) {
					specialChar = true;
					//for now leave out the slash character
					continue;
				}
				
			} else if (byteStr.length() == 8) {
				specialChar = false;
				
				//Stop reading if the byte read is 0x7E
				if (byteStr.equals(END_BYTE)) {
					break;
				}
				
				//add back left out slash
				hexBytes += addByte(SLASH);
			}
		
			
			//Construct the string of hex
			hexBytes += addByte(byteStr);
			
			//True to prevent overwriting the previous voltage
			if (flag) 
				continue;
			
			//Update previous voltage
			volts = currentVolts;
			sleep(10);
		}
	
		//Return empty frame if no bytes was obtained
		if (hexBytes.length() == 0) 
			return new DataFrame("");
		
		//Return a frame containing the byte array of the hex string
		byte[] payload = new BigInteger(hexBytes, 16).toByteArray();
		return new DataFrame(payload);
	}
	
	/*Method to determine whether it should prepare to read 0s or 1s next*/
	private boolean clockShift(double oldVolts) throws InterruptedException {
		//Sleep enough to get ready to read the next bit
		sleep(7*PULSE_WIDTH/10);
		double volts = this.wire.getVoltage(this.deviceName);
		
		//The conditions - True if next bit is the same bit as the last
		boolean condition1 = (oldVolts > 0 && volts > 0);
		boolean condition2 = (oldVolts < 0 && volts < 0);
		return condition1 || condition2;
	}

	/*Refresh the byte string when it becomes an 8-bit binary*/
	private String refreshByte(String byteStr) {
		if (byteStr.length() == 8) {
			return "";
		}
		return byteStr;
	}

	/*Construct the string of bytes in hex*/
	private String addByte(String byteStr) {
		if (byteStr.length() == 8) {
			String hex = Integer.toHexString(Integer.parseInt(byteStr, 2));
			if (hex.length() == 1) 
				hex = '0' + hex;
			
			System.out.println("RECIEVED BYTE = "+ hex + "\nWAITING ...");
			return hex;
		}
		return "";
	}

	
}
