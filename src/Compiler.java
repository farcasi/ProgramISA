import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public abstract class Compiler {
	
	protected static final String[] KEYWORDS = {"+", "-", "/", "*", "goto", "if", "while"};
	protected enum Operation { ADD, SUB, MUL, DIV, GOTO }
	protected enum IfCondition { EQ, NE, LE }
	protected Set<String> vars, labels;
	protected int instructionSize = 0, programCounter = 0;
	protected LinkedList<String> sRegisters = new LinkedList<String>(),
			tRegisters = new LinkedList<String>(),
			labelToPrepend = new LinkedList<String>(),
			ifLabels = new LinkedList<String>(),
			jumpLabels = new LinkedList<String>();
	protected StringBuffer output = new StringBuffer();
	protected String fullCode;
	protected String[] toRemove = new String[0];
	
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
	
	/** Translates C-like code into assembly code. 
	 * 
	 * @param code A string of code written in a C-like language
	 * @return The translation of the input into assembly code
	 */
	public String compile(String code) throws StringNotFoundException {
		clear();
		fullCode = code.replaceAll("\n", "");
		
		String[] lines = fullCode.split("(?<=;)"); // split lines by semi-colon
		System.out.println("Lines: " + java.util.Arrays.toString(lines));
		
		// translate each line into ISA code
		for (int i=0; i<lines.length; i++) {
			System.out.println("Line "+(i+1));
			if (lines[i].replaceAll("\\s+", "").length() < 2) {
				// line contains only ; or nothing
				continue;
			}
			
			translateAndAppendLine(lines[i]); // i*instructionSize is the byte address of this line
			programCounter += instructionSize;
		}
		if (!labelToPrepend.isEmpty()) {
			output.append(labelToPrepend.pollLast()+":\n");
		}
		
		return output.toString();
	}
	
	/** Clear out all the stored data in this compiler to prepare to compile a new program
	 * 
	 */
	private void clear() {
		vars = new HashSet<String>();
		labels = new HashSet<String>();
		programCounter = 0;
		sRegisters.clear();
		tRegisters.clear();
		labelToPrepend.clear();
		ifLabels.clear();
		jumpLabels.clear();
		output.delete(0, output.length());
		fullCode = "";
		toRemove = new String[0];
	}
	
	/** Translates a line to 4-address assembly code and returns the result
	 * 
	 * Note: This method is gigantic, but I'm not sure how to shorten/compartmentalize it
	 * 
	 * @param line	the line to be translated
	 * @param programCounter	the address of the line
	 * @return	the line in ISA code
	 */
	protected void translateAndAppendLine(String line) throws StringNotFoundException {
		if (line.isEmpty()) {
			return;
		}
		StringBuffer temp = new StringBuffer();
		String operation = "", result = "", oper1 = "", oper2 = "";
		LinkedList<String> operands = new LinkedList<String>(), treg = new LinkedList<String>();
		boolean passedAssignmentOperator = false, ignoreFirstParenthesis = false;
		loadVars(line);
		
		// divide string into tokens that can be analyzed
		String[] tokens = line.split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
		if (tokens.length < 1) {
			return;
		}
		System.out.println("Tokens: " + java.util.Arrays.toString(tokens)+"\t"+tokens.length);
		
		// get a label if one exists
		if (tokens[1].contentEquals(":")) {
			// previous token was a label
			labelToPrepend.add(tokens[0].toString());
		}
		
		// find statements in parenthesis and remove them
		for (int i=0; i<tokens.length; i++) {
			if (tokens[i].toLowerCase().matches("if|while")) {
				// don't remove an if-statement
				ignoreFirstParenthesis = true;
			}
			if (tokens[i].contentEquals("(")) {
				if (ignoreFirstParenthesis) {
					ignoreFirstParenthesis = false;
					continue;
				}
				System.out.println("\topen parens");
				tokens[i] = " ";
				// enumerate a new register, and translate the subline
				// between parenthesis as another line
				treg.add("$t"+tRegisters.size()); 
				tRegisters.add(tokens[i]);
				
				// search for close parens
				int cpi = i + 1;
				StringBuffer subLine = new StringBuffer();
				subLine.append(treg.peekLast()+" = ");
				for (; cpi < tokens.length && !tokens[cpi].contentEquals(")"); cpi++) {
					subLine.append(tokens[cpi]);
					tokens[cpi] = " ";
				}
				if (cpi < tokens.length) { 
					tokens[cpi] = " "; // erase the close-parens
				}
				if (subLine.charAt(subLine.length()-1) != ';') {
					subLine.append(";"); // make sure it ends with a ;
				}
				
				// replace a token instead of adding it to operands so it isn't counted twice below
				tokens[i+1] = treg.peekLast();
				
				translateAndAppendLine(subLine.toString());
				programCounter += instructionSize;
				subLine.delete(0, subLine.length());
			}
		}
		
		// put extra operations on a separate line
		while (getNumOperations(tokens) > 1) {
			// search for the second operation and move everything before it to another line
			int i = 0, subStart = 0; 
			boolean inSubLine = false, passedFirstOp = false;
			
			// enumerate a new register, and translate the subline
			// between parenthesis as another line
			treg.add("$t"+tRegisters.size()); 
			tRegisters.add(tokens[i]);
			
			StringBuffer subLine = new StringBuffer();
			subLine.append(treg.peekLast()+" = ");
			
			// create a new subLine from the '=' symbol to the 2nd operation symbol
			for (i=0; i<tokens.length; i++) {
				if (!inSubLine) {
					// basically before the '=', we shouldn't reach after 2nd operation symbol
					if (tokens[i].contentEquals("=")) {
						subStart = i + 1;
						inSubLine = true;
					}
				} else {
					// stop at 2nd operation symbol
					if (isOperation(tokens[i])) {
						if (passedFirstOp) {
							break;
						} else {
							passedFirstOp = true;
						}
					}
					subLine.append(tokens[i]);
					tokens[i] = " ";
				}
			}
			
			subLine.append(";"); // make sure it ends with a ;
			tokens[subStart] = treg.peekLast();
			
			// make a separate instruction out of the new subLine
			translateAndAppendLine(subLine.toString());
			programCounter += instructionSize;
			subLine.delete(0, subLine.length());
		}
		
		// Parse the line
		for (int i=0; i<tokens.length; i++) {
			// determine what the token is and translate it
			System.out.print(tokens[i]);// TODO: remove
			
			if (tokens[i].contentEquals(";")) {
				// end of line
				System.out.println("\teol");
				operands.add(temp.toString());
				temp.delete(0, temp.length());
				break;
			} else if (tokens[i].matches("[\\s:]") || tokens[i].length() < 1) { // either whitespace or colon
				// whitespace
				System.out.println("\tnothing");
				continue;
			} else if (tokens[i].contentEquals("=")) {
				// equals sign, we've passed the result part
				System.out.println("\tequals");
				if ((i+1 < tokens.length && tokens[i+1].contentEquals("=")) || 
					(i-1 >= 0 && tokens[i-1].contentEquals("="))) {
					// equality check, not assignment
					operation = "beq";
					jumpLabels.add("True"+jumpLabels.size());
				} else {
					passedAssignmentOperator = true;
				}
			} else if (tokens[i].charAt(0) == '$') {
				// the beginning of a register name
				// get the rest of the register name
				System.out.println("\tregister");
				int numi = i + 1;
				temp.append(tokens[i]);
				for (; numi < tokens.length && tokens[numi].matches("\\w"); numi++) {
					temp.append(tokens[numi]);
					tokens[numi] = " ";
				}

				if (!passedAssignmentOperator) {
					result = temp.toString();
				} else {
					operands.add(temp.toString());
				} 
				temp.delete(0, temp.length());
			} else if (isNumeric(tokens[i])) {
				// number
				System.out.println("\tnum");
				
				// get the rest of the decimal number (if applicable)
				int numi = i + 1;
				temp.append(tokens[i]);
				for (; numi < tokens.length && (isNumeric(tokens[numi]) || tokens[numi].contentEquals(".")); numi++) {
					temp.append(tokens[numi]);
					tokens[numi] = " ";
				}
				
				operands.add(temp.toString());
				temp.delete(0, temp.length());
			} else if (tokens[i].length() > 1) {
				// a word
				System.out.println("\tword");
				if (tokens[i].toLowerCase().contentEquals("goto")) {
					operation = "j";
				} else if (tokens[i].toLowerCase().contentEquals("if")) {
					// if statement, handle separately
					handleIfStatement(line);
					
					// skip the rest of this line
					return;
				} else if (tokens[i].toLowerCase().contentEquals("else")) {
					// an else statement, presumably one we reached already
					if (toRemove.length > 0) {
						int ti = i;
						
						// check if this else statement is one we should remove
						for (; ti < tokens.length; ti++) {
							if (tokens[ti].contentEquals(toRemove[0])) {
								break;
							}
						}
						
						// make sure we have a match
						int tempIndex = 0;
						for (; tempIndex < toRemove.length; tempIndex++) {
							if (!tokens[ti+tempIndex].contentEquals(toRemove[tempIndex])) {
								break;
							}
						}
						
						if (ti < tokens.length && tempIndex >= toRemove.length) {
							// found a match, stop looking at this line
							return;
						}
					}
				} else if (tokens[i].toLowerCase().contentEquals("while")) {
					// if statement, handle separately
					handleWhileStatement(line);
					
					// skip the rest of this line
					return;
				} else if (tokens[i].contains("[")) {
					// an array index
					treg.add("$t"+tRegisters.size());
					if (!passedAssignmentOperator) {
						result = varToReg(treg.peekLast());
					} else {
						temp.append(treg.peekLast());
					}
					
					// add lines to load the indexed value into the tregister
					addArrayLoadingLine(tokens[i], treg.peekLast());
				} else {
					if (!passedAssignmentOperator) {
						result = varToReg(tokens[i]);
					} else {
						temp.append(tokens[i]);
					}
				}
			} else if (Character.isLetter(tokens[i].charAt(0))) {
				// a letter (a variable)
				System.out.println("\tchar");
				if (i+1 < tokens.length && tokens[i+1].contentEquals(":")) {
					temp.append(tokens[i]);
				} else {
					if (!passedAssignmentOperator) {
						result = varToReg(tokens[i]);
					} else {
						operands.add(tokens[i]);
					}
				}
			} else if (tokens[i].contentEquals("(")) {
				System.out.println("\topen parens");
				tokens[i] = " ";
				if (getNumOperations(tokens) < 2) {
					for (int cpi = i; cpi < tokens.length; cpi++) {
						if (tokens[cpi].contentEquals(")")) {
							tokens[cpi] = " ";
							break;
						}
					}
					continue;
				}
				// enumerate a new register, and translate the subline
				// between parenthesis as another line
				treg.add("$t"+tRegisters.size()); 
				tRegisters.add(tokens[i]);
				operands.add(treg.peekLast());
				
				// search for close parens
				int cpi = i + 1;
				StringBuffer subLine = new StringBuffer();
				subLine.append(treg.peekLast()+" = ");
				for (; cpi < tokens.length && !tokens[cpi].contentEquals(")"); cpi++) {
					subLine.append(tokens[cpi]);
					tokens[cpi] = " ";
				}
				if (cpi < tokens.length) { 
					tokens[cpi] = " "; // erase the close-parens
				}
				if (subLine.charAt(subLine.length()-1) != ';') {
					subLine.append(";"); // make sure it ends with a ;
				}
				
				translateAndAppendLine(subLine.toString());
				programCounter += instructionSize;
				subLine.delete(0, subLine.length());
			} else {
				// a non-word, non-letter, non-number, non-ignorable-character
				// must be an operation
				System.out.println("\toperation");
				
				switch(getOperation(tokens[i])) {
				case ADD: operation = "add"; break;
				case SUB: operation = "sub"; break;
				case MUL: operation = "mul"; break;
				case DIV: operation = "div"; break;
				case GOTO: operation = "j"; break;
				default: throw new StringNotFoundException("No operation found.");
				}
			}
		}
		
		// create the output line
		if (!operands.isEmpty()) {
			oper1 = varToReg(operands.poll());
		}
		if (!operands.isEmpty()) {
			oper2 = varToReg(operands.poll());
		}
		
		writeLine(operation, result, oper1, oper2);
		
		// cleanup
		while (!treg.isEmpty()) {
			String var = regToVar(treg.pollLast());
			tRegisters.remove(var);
		}
	}
	
	/** Writes the line to the output in the ISA language
	 * 
	 * @param operation
	 * @param result
	 * @param oper1
	 * @param oper2
	 */
	protected abstract void writeLine(String operation, String result, String oper1, String oper2);
	
	/** Returns the register that holds the variable var.
	 * If the input is already a register, returns var unchanged.
	 * 
	 * @param var The name of a variable to translate to a register
	 * @return The name of the register where the variable is stored
	 */
	protected String varToReg(char var) {
		return varToReg(String.valueOf(var));
	}
	
	/** Returns the register that holds the variable var.
	 * If the input is already a register, returns var unchanged.
	 * 
	 * @param var The name of a variable to translate to a register
	 * @return The name of the register where the variable is stored
	 */
	protected String varToReg(String var) {
		if (var.length() < 1 || var.charAt(0) == '$') {
			return var;
		} else if (tRegisters.contains(var)) {
			return "$t"+tRegisters.indexOf(var);
		} else if (sRegisters.contains(var)) {
			return "$s"+sRegisters.indexOf(var);
		} else {
			return var;
		}
	}
	
	/** Returns the variable that is held in the register reg.
	 * If the input is already a variable, returns reg unchanged.
	 * 
	 * @param reg The name of a register to translate to a variable
	 * @return The name of the variable held in reg
	 */
	protected String regToVar(String reg) {
		String[] parts = reg.split("(?=[a-zA-Z])|(?<=[a-zA-Z])");
		if (reg.charAt(0) != '$') {
			return reg;
		} else if (parts[1].matches("t")) {
			return tRegisters.get(Integer.parseInt(parts[2]));
		} else if (parts[1].matches("s")) {
			return sRegisters.get(Integer.parseInt(parts[2]));
		} else {
			return reg;
		}
	}
	
	/** Prepends the current line of code with the lines to initialize an array
	 * 
	 * @param token	The array name and index, eg. "A[I]"
	 * @param treg The name of the tregister that will replace the array name in the current line 
	 * @return the new programCounter after adding the lines
	 */
	protected abstract void addArrayLoadingLine(String token, String treg);
	
	/** Parses, translates, and appends the if statement into ISA code
	 * 
	 * @param line An if statement
	 * @throws StringNotFoundException 
	 */
	protected abstract void handleIfStatement(String line) throws StringNotFoundException;
	
	/** Parses, translates, and appends the while statement into ISA code
	 * 
	 * @param line An if statement
	 * @throws StringNotFoundException 
	 */
	protected abstract void handleWhileStatement(String line) throws StringNotFoundException;
	
	/** Returns the operands in parenthesis to be compared in a while/if statement
	 * 
	 * @param line a line containing a while/if statement and a condition in parenthesis
	 * @return the operands to compare
	 */
	protected abstract LinkedList<String> getOperandsToCompare(String line);
	
	/** Gets the variables in a line of code and loads them as appropriate for the compiler 
	 * Variables are identified by being single, capital-letter characters.
	 * 
	 * @param code C-like code
	 */
	protected abstract void loadVars(String line);
	
	/** Returns the Labels in a piece of code. Labels are identified by 
	 * being characters followed by a colon (:).
	 * 
	 * @param code C-like code
	 * @return The labels in the code
	 */
	protected Set<String> getLabels(String code) {
		String[] words = code.split("\\s+|(?<=\\W)(?=\\w)|\\s+|(?<=\\w)(?=\\W)|\\s+");
		Set<String> labels = new HashSet<String>();
		
		for (String w : words) {
			if (w.matches("[a-zA-Z]+:")) {
				labels.add(w);
			}
		}
		
		return labels;
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
	protected Operation getOperation(String line) throws StringNotFoundException {
		int start = line.indexOf('=');
		String part = line.substring(start+1).toLowerCase();
		if (part.contains("+")) {
			return Operation.ADD;
		} else if (part.contains("-")) {
			return Operation.SUB;
		} else if (part.contains("/")) {
			return Operation.DIV;
		} else if (part.contains("*")) {
			return Operation.MUL;
		} else if (part.contains("goto")) {
			return Operation.GOTO;
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
	
	/** Returns the if condition for this line
	 * e.g. equal, not equal, less than
	 * 
	 * @return the first if condition on this line
	 */
	protected IfCondition getIfCondition(String line) throws StringNotFoundException {
		if (line.contains("==")) {
			return IfCondition.EQ;
		} else if (line.contains("!=")) {
			return IfCondition.NE;
		} else if (line.contains("<")) {
			return IfCondition.LE;
		} else {
			throw new StringNotFoundException("No if-condition found: "+line);
		}
	}
	
	// credit to stackoverflow for this method
	protected static boolean isNumeric(String str)	{
		return str.matches("[-+]?\\d*\\.?\\d+");
	}
	
	/** Checks for the first else after the given if statement (if found), and handles any it finds
	 * 
	 * @param ifStatement 
	 * @throws StringNotFoundException 
	 */
	protected void handleElse(String ifStatement) throws StringNotFoundException {
		int start = Math.max(fullCode.indexOf(ifStatement), 0);
		String code = fullCode.substring(start);
		int elseStart = code.toLowerCase().indexOf("else"); 
		if (elseStart < 0) {
			return;
		}
		
		// otherwise we found an else statement
		String subCode = code.substring(elseStart);
		int elseStop = subCode.indexOf(';')+1;
		if (elseStop == 0) {
			elseStop = subCode.length();
		}
		
		jumpLabels.add("Exit"+jumpLabels.size());
		translateAndAppendLine(subCode.substring(5, elseStop)); // +5 to start after "else "
		programCounter += instructionSize;
		output.append("\tj "+jumpLabels.peekLast()+"\n");
		programCounter += instructionSize;
		System.out.println(jumpLabels);
		
		toRemove = subCode.substring(5, elseStop).split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
	}
	
	/** Returns true if the word is a keyword for this compiler
	 * 
	 * @param word
	 * @return
	 */
	protected static boolean isKeyword(String word) {
		return Arrays.asList(KEYWORDS).contains(word.toLowerCase());
	}
}
