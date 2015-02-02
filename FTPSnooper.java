package FTPSnooper;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.*;
import java.net.*;
import java.util.*;
import java.io.*;


// leejeanne 1/2015

public class FTPSnooper {
    
    // Hostname of the FTP server to connect to.
    private final String hostname;
    
    // Directory on server to analyze.
    private final String directory;
    
    // Pattern object for file regular expression.
    private final Pattern filePattern;
    
    private ArrayList<String> files = new ArrayList<String>();
    private ArrayList<String> keyHolder = new ArrayList<String>();
    
    // Results that are generated with filenames given as the keys
    // and 'first 20 lines of each file' given as the values.
    // See fetch() method for dummy example data of layout.
    private final HashMap<String, String> fileInfo = new HashMap<String, String>();
    
    public FTPSnooper(String hostname, String directory,
                      String filenameRegularExpression) {
        this.hostname = hostname;
        this.directory = directory;
        this.filePattern = Pattern.compile(filenameRegularExpression);
    }
    
    /**
     * Fetch the required file overviews from the FTP server.
     *
     * @throws IOException
     */
    public void fetch() throws IOException {
        
        Socket socket = new Socket(hostname, 21);
        
        BufferedWriter writer = null;
        BufferedReader reader = null;
        
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        
        // input USERname
        String user = "USER anonymous\r\n";
        writer.write(user);
        writer.flush();
        String response = reader.readLine();
        
        // username error check
        if (!response.startsWith("220 ")) {
        	System.out.println("Bad username");
        	System.exit(1);
        }
        
        // input password
        String pass = "PASS ident\r\n";
        writer.write(pass);
        writer.flush();
        response = reader.readLine();
        response = reader.readLine();
        
        // password error check
        if (!response.startsWith("230 ")) {
        	System.out.println("Bad password");
        	System.exit(1);
        }
        
        // input PASV
        String pasv = "PASV\r\n";
        writer.write(pasv);
        writer.flush();
        response = reader.readLine();
        
        // pasv error check
        if (!response.startsWith("227 ")) {
        	System.out.println("Passive mode fail");
        	System.exit(1);
        }
        
        // find dataConnection port value from server input pasv line
        int portValue = -1;
        
        try {
            portValue = findPort(response);
        } catch (Exception e) {
            System.out.println("Couldn't find portValue");
            System.exit(1);
        }
        
        // run NLST and look through files for matches with the regex
        String nextCommand = "NLST";
        // start dataConnection thread!
        dataConnection data = new dataConnection(hostname, portValue, nextCommand, "null");
        data.start();
        
        String command = ("NLST " + directory + "\n");
        writer.write(command);
        writer.flush();
        
        // wait here until data connection thread ends
        try {
            data.join();
        } catch (InterruptedException e1) {
            System.out.println("Couldn't join the threads");
            System.exit(1);
        }
        
        // resume
        for (int i=0; i<keyHolder.size(); i++) {
        	
	        response = reader.readLine();
	        // accounting for first line that follows after a transfer
	        if (response.startsWith("150 ")) {
	            response = reader.readLine();
	        } else {
	        	System.out.println("Bad NLST request");
	        }
	        
	        // run pasv again to find new port value for dataConnection
	        command = "PASV\r\n";
	        writer.write(command);
	        writer.flush();
	        
	        // accounting for second line that follows after a transfer
	        if (response.startsWith("226 ")) {
	            response = reader.readLine();
	        } else {
	        	System.out.println("Bad NLST request");
	        }
	        
	        // find new portValue for opening new dataConnection
	        try {
	            portValue = findPort(response);
	        } catch (Exception e) {
	            System.out.println("Couldn't find new portValue");
	            System.exit(1);
	        }
	        
	        // change into right directory first time around
	        if (i == 0) {
		        String cwd = "cwd " + directory + "\r\n";
		        writer.write(cwd);
		        writer.flush();
		        response = reader.readLine();
	        } 
	        
	        // get all the fileNames so when RETR, can place the text concatenation string
	        // into correct position
	        String keyFile = keyHolder.get(i);
	        
            nextCommand = "RETR";
            data = new dataConnection(hostname, portValue, nextCommand, keyFile);
            data.start();
            
            command = "retr " + keyFile + "\r\n";
            writer.write(command);
            writer.flush();
            
            try {
                data.join();
            } catch (InterruptedException e) {
                System.out.println("Couldn't join");
                System.exit(1);
            }
        }
        
    }
    
    // finding new port number when being passed PASV return call
    public int findPort (String response) throws Exception {
        
        String port = null;
        int portValue = -1;
        int first = response.indexOf('(');
        int last = response.indexOf(')', first + 1);
        if (last > 0) {
            String link = response.substring(first + 1, last);
            StringTokenizer coin = new StringTokenizer(link, ",");
            try {
                port = coin.nextToken() + "." + coin.nextToken() + "." + coin.nextToken() + "." + coin.nextToken();
                portValue = Integer.parseInt(coin.nextToken()) * 256
                + Integer.parseInt(coin.nextToken());
            } catch (Exception e) {
                throw new IOException("PASV got bad link info");
            }
            
        }
        
        return portValue;
    }
    
    
    /**
     * Return the result of the fetch command.
     * @return The result as a map with keys = "filenames" and values = "first 20 lines of each file".
     */
    
    public Map<String, String> getFileInfo() {
        return fileInfo;
    }
    
    
    
    class dataConnection extends Thread {
        
        String hostname;
        int portValue;
        String nextCommand;
        Socket dataConn;
        String response;
        BufferedReader dataReader;
        String key;
        
        public dataConnection (String hostname, int portValue, String nextCommand, String key) {
            this.hostname = hostname;
            this.portValue = portValue;
            this.nextCommand = nextCommand;
            this.key = key;
        }
        
        
        public void run() {
            
            try {
                
                Socket dataConn = new Socket(hostname, portValue);
                BufferedReader dataReader = new BufferedReader(new InputStreamReader(dataConn.getInputStream()));
                String response = dataReader.readLine();
                
                
                // if NLST, add names of files into arrayList
                if (nextCommand == "NLST") {
                    while (response != null) {
                        files.add(response);
                        response = dataReader.readLine();
                    }
                    nlst();
                }
                
                // if RETR, concatenate lines of text from file into one string
                // store the string as value for key
                if (nextCommand == "RETR") {
                    StringBuilder sb = new StringBuilder();
                    
                    for (int i=0; i<20; i++) {
                        sb.append(response).append("\r\n");
                        response = dataReader.readLine();
                    }
                    String result = sb.toString();
                    fileInfo.put(key, result);
                }
                
            } catch (IOException e) {
                e.printStackTrace();
            }
            
        }
        
        // NLST METHOD: checks the strings in the arraylist containing the file names from directory
        // for matching against the filePattern
        // stores the matched file names in different arraylist
        // matched file names added as keys in HashMap
        public void nlst() {
            for (String string : files) {
                Matcher regexMatcher = filePattern.matcher(string);
                if (regexMatcher.matches()) {
                    String dummyText = "hello";
                    keyHolder.add(string);
                    fileInfo.put(string, dummyText);
                }
            }
        }
        
    }
}
