import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;


public class FileHelper { 
	
	public static StringBuffer readFile(String path) {
		StringBuffer sb = new StringBuffer();
		
		try {
			byte[] encoded = Files.readAllBytes(Paths.get(path));
			String temp = new String(encoded, "UTF-8");
			
			// clean it up a bit for the loader
			temp.replaceAll("\r", "");
			sb.append(temp);
			
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
		
		return sb;
	}
	
	public static Object readObjectFile(String path) {
		Object o = null;
		ObjectInputStream ois = null;
		
		try {
		    ois = new ObjectInputStream(new FileInputStream(path));
			o = ois.readObject();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} finally {
            try {
                // Close the writer regardless of what happens...
                ois.close();
            } catch (Exception e) {
            }
        } 
		
		return o;
	}
	
	// Credit to stackoverflow for this method
	public static void writeFile(String filename, String output) {
		BufferedWriter writer = null;
        try {
            //create a temporary file
            File logFile = new File(filename);

            writer = new BufferedWriter(new FileWriter(logFile));
            writer.write(output);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                // Close the writer regardless of what happens...
                writer.close();
            } catch (Exception e) {
            }
        }
	}
	
	// Credit to stackoverflow for this method
	public static void writeFile(String filename, Object object) {
		ObjectOutputStream writer = null;

        try {
            //create a temporary file
            File logFile = new File(filename);
            
            writer = new ObjectOutputStream(new FileOutputStream(logFile));
            writer.writeObject(object);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                // Close the writer regardless of what happens...
                writer.close();
            } catch (Exception e) {
            }
        }
	}
}
