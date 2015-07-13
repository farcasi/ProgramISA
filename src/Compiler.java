import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public class Compiler {
	
	protected Set<String> vars, labels; // TODO: remove if unused
	protected enum operation { ADD, SUB, MUL, DIV, GOTO }
	protected int instructionSize = 0, programCounter = 0;
	protected LinkedList<String> sRegisters = new LinkedList<String>(),
			tRegisters = new LinkedList<String>(),
			labelToPrepend = new LinkedList<String>(),
			ifLabels = new LinkedList<String>(),
			jumpLabels = new LinkedList<String>();
	protected StringBuffer output = new StringBuffer();
	
	/** Returns a compiler for the given architecture.
	 * 
	 * @param architecture An ISA architecture 
	 * @return A compiler for the given architecture
	 */
	public static Compiler getCompiler(ISA architecture) {
		Compiler c;
		switch(architecture) {
		case MM4ADDRESS: c = new MM4AddressCompiler(); break;
		default: c = new MM4AddressCompiler();
		}
		
		System.out.println("\nCompiler created for "+architecture);
		
		return c;
	}
	
	/** Translates C-like code into assembly code. This method is not fully implemented here, 
	 * but should be implemented in all subclasses.
	 * 
	 * @param code A string of code written in a C-like language
	 * @return The translation of the input into assembly code
	 */
	public String compile(String code) throws StringNotFoundException {
		String fullCode = code.replaceAll("\n", "");
		loadVars(fullCode);
		
		return output.toString();
	}
	
	/** Gets the variables in a piece of code and loads them into s-registers. 
	 * Variables are identified by being single, capital-letter characters.
	 * 
	 * @param code C-like code
	 * @return The variables in the code
	 */
	protected void loadVars(String code) {
		String[] words = code.split("\\s+|(?<=\\W)(?=\\w)|\\s+|(?<=\\w)(?=\\W)|\\s+");
		vars = new HashSet<String>();
		
		for (String w : words) {
			if (w.matches("[a-zA-Z]")) {
				vars.add(w); // this should be 1 character, from the "if" statement
			}
		}

		sRegisters.addAll(vars);
	}
	
	/** Returns the Labels in a piece of code. Labelsare identified by 
	 * being characters followed by a colon (:).
	 * 
	 * @param code C-like code
	 * @return The labels in the code
	 */
	protected Set<String> getLabels(String code) {
		String[] words = code.split("\\s+|(?<=\\W)(?=\\w)|\\s+|(?<=\\w)(?=\\W)|\\s+");
		Set<String> vars = new HashSet<String>();
		
		for (String w : words) {
			if (w.matches("[a-zA-Z]")) {
				vars.add(w); // this should be 1 character, from the "if" statement
			}
		}
		
		return vars;
	}
	
	/** Returns the name of the variable/label that is the result of this line's operation.
	 * e.g. In "A = B + C;" the result is A.
	 *  
	 * @param line
	 * @return
	 */
	protected String getResult(String line) {
		int start = line.indexOf('=');
		if (start > -1) {
			String part = line.substring(0, start);
			for (String v : vars) {
				if (part.contains(v)) {
					// assume there is only 1 result
					return v;
				}
			}
		} else {
			start = line.indexOf("Goto");
			String part = line.substring(start+1);
			for (String l : labels) {
				if (part.contains(l)) {
					// assume there is only 1 label
					return l;
				}
			}
		}
		return "";
	}
	
	/** Returns the operation for this line
	 * 
	 * @return the first operation on this line
	 */
	protected operation getOperation(String line) throws StringNotFoundException {
		int start = line.indexOf('=');
		String part = line.substring(start+1).toLowerCase();
		if (part.contains("+")) {
			return operation.ADD;
		} else if (part.contains("-")) {
			return operation.SUB;
		} else if (part.contains("/")) {
			return operation.DIV;
		} else if (part.contains("*")) {
			return operation.MUL;
		} else if (part.contains("goto")) {
			return operation.GOTO;
		} else {
			throw new StringNotFoundException("No operation found: "+line);
		}
	}
	
	/** Returns the number of operations (+, -, *, /, Goto) found on the line
	 * 
	 * @param line a line to check
	 * @return the number of operations
	 */
	protected int getNumOperations(String[] tokens) {
		int total = 0;
		for (String s : tokens) {
			if (isOperation(s)) {
				total++;
			}
		}
		return total;
	}
	
	/** Returns true if the token is an operation recognized by this compiler
	 * 
	 * @param token
	 * @return
	 */
	protected boolean isOperation(String token) {
		return token.toLowerCase().matches("[\\-*+\\/]|goto");
	}
	
	/** Returns true if the line contains an operation recognized by this compiler
	 * 
	 * @param line
	 * @return
	 */
	protected boolean hasOperation(String line) {
		return line.toLowerCase().contains("[\\-*+\\/]|goto");
	}
	
	// credit to stackoverflow for this method
	protected static boolean isNumeric(String str)	{
		return str.matches("[-+]?\\d*\\.?\\d+");
	}
}
