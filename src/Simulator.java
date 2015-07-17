import java.io.IOException;
import java.sql.Timestamp;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;


public class Simulator {

	public final Logger logger = Logger.getLogger(Simulator.class.getName());
	
	FileHandler fh;
	
	public Simulator() {
		initializeLogFile();
	}
	
	public static void main(String[] args) {
		Simulator ms = new Simulator();
		StringBuffer output = new StringBuffer();
		
		try {
			output.append(ms.simulate(args) + "\n");
		} catch (Exception e) {
			ms.logger.log(Level.SEVERE, e.getMessage(), e);
		}
		
		if (output.length() > args.length) { // there's at least 1 \n for each arg, so this is the minimum
			System.out.println("Output:\n"+output);
			FileHelper.writeFile("Simulator Output "+(new Timestamp(System.currentTimeMillis())), output.toString()); //TODO: create file
		} else {
			ms.logger.info("Output was empty. No output file created.");
		}
	}
	
	/** Creates a file to store the log output and attaches a file writer to the log.
	 * 
	 */
	private void initializeLogFile() {
		try {
			// credit for this try block to Sri Harsha Chilakapati on StackOverflow
	        // This block configure the logger with handler and formatter  
	        fh = new FileHandler("SimulatorLogFile.log", true);  
	        logger.addHandler(fh);
	        fh.setFormatter(new SimpleFormatter());

	        // the following statement is used to log any messages  
	        logger.info("Simulator started.");
	    } catch (SecurityException e) {  
	    	e.printStackTrace();
	    	System.exit(-1);
	    } catch (IOException e) {  
	    	e.printStackTrace();
	    	System.exit(-1);
	    }
	}
	
	/** Reads the code in a file, compiles it in different instruction-set architectures,
	 * and returns the compiled codes.
	 * 
	 * @param file The path to a file containing C-like code
	 * @return The compiled forms of the file's code under different ISAs 
	 */
	public String simulate(String[] files) {
		StringBuffer input, output = new StringBuffer();
		Compiler c;
		
		for (ISA i : ISA.values()) {
			output.append("Architecture: " + i + "\n");
			try {
				c = Compiler.getCompiler(i);
				for (String file : files) {
					input = FileHelper.readFile(file);
					output.append("File: " + file + "\nCode:\n");
					output.append(c.compile(input.toString()) + "\n");
				}
			} catch (RuntimeException re) {
				throw re;
			} catch (Exception e) {
				output.append(e.getMessage());
				logger.log(Level.SEVERE, e.getMessage(), e);
			}
			output.append("\n");
		}
		
		return output.toString();
	}

}
