import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public abstract class Compiler {
	
	protected static final String[] KEYWORDS = {"+", "-", "/", "*", "goto", "if", "else", "while", "switch"};
	protected static final String[] IDENTIFIERS = {"byte", "short", "int", "long", "float", "double", "boolean", "char"};
	protected enum Operation { ADD, SUB, MUL, DIV, GOTO }
	protected enum IfCondition { EQ, NE, LE }
	protected Set<String> vars, labels;
	protected int programBits = 0, instructionSize = 0, programCounter = 0, 
			numInstructions = 0, memAccesses = 0;
	protected LinkedList<String> tempAddrs = new LinkedList<String>(),
			labelsToPrepend = new LinkedList<String>(),
			ifLabels = new LinkedList<String>(),
			jumpLabels = new LinkedList<String>(),
			currentArgs = new LinkedList<String>(),
			stack = new LinkedList<String>();
	protected StringBuffer output = new StringBuffer(), 
			bracketStatement = new StringBuffer(),
			functionsToAdd = new StringBuffer();
	protected String fullCode;
	protected String[] toRemove = new String[0];
	protected HashMap<String, LinkedList<String>> functions = new HashMap<String, LinkedList<String>>(); // name -> args
	protected boolean insideBrackets = false, insideFunctionDeclaration = false;
	
	/** Returns a compiler for the given architecture.
	 * 
	 * @param architecture An ISA architecture 
	 * @return A compiler for the given architecture
	 */
	public static Compiler getCompiler(ISA architecture) {
		Compiler c;
		switch(architecture) {
		case MM4ADDRESS: c = new MM4AddressCompiler(); break;
		case MM3ADDRESS: c = new MM3AddressCompiler(); break;
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
			if (lines[i].replaceAll("\\s+", "").length() < 1) {
				// line contains only ; or nothing
				continue;
			}
			
			translateAndAppendLine(lines[i]);
		}
		if (!labelsToPrepend.isEmpty()) {
			output.append(labelsToPrepend.pollLast()+":\n");
			numInstructions++;
		}
		if (!jumpLabels.isEmpty()) {
			output.append(jumpLabels.pollLast()+":\n");
			numInstructions++;
		}
		if (functionsToAdd.length() > 0) {
			output.append(functionsToAdd);
			// and we already counted the instructions size/number when we put them in functionsToAdd
		}
		/* TODO: Uncomment this
		output.append("\nInstruction count:\t"+numInstructions+"\n"
				+ "Size of resulting code:\t"+programBits+" bits\n"
				+ "# of memory accesses:\t"+memAccesses+"\n");
		*/
		return output.toString();
	}
	
	/** Clear out all the stored data in this compiler to prepare to compile a new program
	 * 
	 */
	protected void clear() {
		vars = new HashSet<String>();
		labels = new HashSet<String>();
		programBits = 0;
		programCounter = 0;
		numInstructions = 0;
		tempAddrs.clear();
		labelsToPrepend.clear();
		ifLabels.clear();
		jumpLabels.clear();
		functions.clear();
		currentArgs.clear();
		stack.clear();
		output.delete(0, output.length());
		bracketStatement.delete(0, bracketStatement.length());
		functionsToAdd.delete(0, functionsToAdd.length());
		fullCode = "";
		toRemove = new String[0];
		insideBrackets = false;
		insideFunctionDeclaration = false;
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
		} else if (insideBrackets) {
			// put all the lines inside brackets into 1 line to deal with them together
			if (line.contains("}")) {
				int bracket = line.indexOf("}")+1;
				bracketStatement.append(line.substring(0, bracket));
				insideBrackets = false;
				if (bracketStatement.indexOf("switch") > -1) {
					handleSwitchStatement(bracketStatement.toString());
				} else {
					handleFunctionDeclaration(bracketStatement.toString());
				}
				bracketStatement.delete(0, bracketStatement.length());
				
				// exit if we're done with this line 
				line = line.substring(bracket, line.length());
				if (line.isEmpty()) {
					return;
				}
			} else {
				bracketStatement.append(line);
				return;
			}
		}
		String operation = "", result = "", oper1 = "", oper2 = "";
		LinkedList<String> operands = new LinkedList<String>(), temps = new LinkedList<String>();
		boolean passedAssignmentOperator = false, ignoreFirstParenthesis = false, ignoreFirstLabel = false;
		loadVars(line);
		
		// divide string into tokens that can be analyzed
		String[] tokens = line.split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
		if (tokens.length < 1) {
			return;
		}
		System.out.println("Tokens: " + java.util.Arrays.toString(tokens)+"\t"+tokens.length);
		
		// get a label if one exists
		for (int i=0; i<tokens.length; i++) {
			if (tokens[i].contentEquals("case")) {
				ignoreFirstLabel = true;
			}
			if (tokens[i].contentEquals(":") && i > 0) {
				if (ignoreFirstLabel) {
					ignoreFirstLabel = false;
					continue;
				}
				// previous token was a label
				labelsToPrepend.add(tokens[i-1]);
				labels.add(labelsToPrepend.peekLast());
				tokens[i-1] = " ";
				tokens[i] = " ";
			} else if (tokens[i].endsWith(":")) {
				if (ignoreFirstLabel) {
					ignoreFirstLabel = false;
					continue;
				}
				labelsToPrepend.add(tokens[i].substring(0, tokens[i].length()-1)); // exlcude the ":" from the label
				labels.add(labelsToPrepend.peekLast());
				tokens[i] = " ";
			}
		}
		
		// find statements in parenthesis and remove them
		for (int i=0; i<tokens.length; i++) {
			if (tokens[i].toLowerCase().matches("if|while|switch")) {
				// don't remove an if-statement
				ignoreFirstParenthesis = true;
			}
			if (tokens[i].contentEquals("(")) {
				if (ignoreFirstParenthesis) {
					ignoreFirstParenthesis = false;
					continue;
				}
				if (i > 0 && tokens[i-1].matches("\\w+")) { 
					// function call or declaration, don't analyze contents
					continue;
				}
				System.out.println("\topen parens");
				tokens[i] = " ";
				// enumerate a new register, and translate the subline
				// between parenthesis as another line
				temps.add(getTempAddr());
				tempAddrs.add(tokens[i]);
				
				// search for close parens
				int cpi = i + 1;
				StringBuffer subLine = new StringBuffer();
				subLine.append(temps.peekLast()+" = ");
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
				tokens[i+1] = temps.peekLast();
				
				translateAndAppendLine(subLine.toString());
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
			temps.add(getTempAddr()); 
			tempAddrs.add(tokens[i]);
			
			StringBuffer subLine = new StringBuffer();
			subLine.append(temps.peekLast()+" = ");
			
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
			tokens[subStart] = temps.peekLast();
			
			// make a separate instruction out of the new subLine
			translateAndAppendLine(subLine.toString());
			subLine.delete(0, subLine.length());
		}
		
		// Parse the line
		for (int i=0; i<tokens.length; i++) {
			// determine what the token is and translate it
			System.out.print(tokens[i]);// TODO: remove
			
			// start matching characters
			if (tokens[i].contentEquals(";")) {
				// end of line
				System.out.println("\teol");
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
					labels.add(jumpLabels.peekLast());
				} else {
					passedAssignmentOperator = true;
				}
			} else if (tokens[i].charAt(0) == '$') {
				// the beginning of a register name
				// get the rest of the register name
				System.out.println("\tregister");
				int numi = i + 1;
				StringBuffer temp = new StringBuffer();
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
				operands.add(tokens[i]);
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
				} else if (tokens[i].toLowerCase().contentEquals("switch")) {
					// switch statement, handle separately
					if (line.contains("{")) {
						bracketStatement.append(line);
						insideBrackets = true;
					}
					return;
				} else if (i+1 < tokens.length && tokens[i+1].contentEquals("(")) {
					if (line.contains("{")) {
						// function declaration
						bracketStatement.append(line);
						insideBrackets = true;
						return;
					} else {
						// function call
						handleFunctionCall(line.substring(line.indexOf(tokens[i]), line.indexOf(")")+1));
						if (line.substring(line.indexOf(")")+1).matches(".*\\w.*")) {
							// line contains something beside white spaces and semicolon
							for (int j = i; !line.substring(line.indexOf(")")+1).contains(tokens[j]); j++) {
								// erase everything up to post-function call
								tokens[j] = " ";
							}
							operands.add(getReturnValueName());
							if (!insideFunctionDeclaration) {
								labelsToPrepend.add(jumpLabels.pollLast());
							}
							jumpLabels.add(getReturnAddressName());
						} else {
							// otherwise line contains nothing else
							return;
						}
					}
				} else if (tokens[i].contains("[")) {
					// an array index
					temps.add(getTempAddr());
					if (!passedAssignmentOperator) {
						result = varToISAVar(temps.peekLast());
					} else {
						operands.add(temps.peekLast());
					}
					
					// add lines to load the indexed value into the tregister
					addArrayLoadingLine(tokens[i], temps.peekLast());
				} else if (isIdentifier(tokens[i])) {
					// ignore it for now
				} else if (tokens[i].toLowerCase().contentEquals("return")) {
					result = getReturnValueName();
					passedAssignmentOperator = true;
				} else {
					if (!passedAssignmentOperator) {
						result = varToISAVar(tokens[i]);
					} else {
						operands.add(tokens[i]);
					}
				}
			} else if (Character.isLetter(tokens[i].charAt(0))) {
				// a letter (a variable)
				System.out.println("\tchar");
				if (!passedAssignmentOperator) {
					result = varToISAVar(tokens[i]);
				} else {
					operands.add(tokens[i]);
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
				temps.add(getTempAddr()); 
				tempAddrs.add(tokens[i]);
				operands.add(temps.peekLast());
				
				// search for close parens
				int cpi = i + 1;
				StringBuffer subLine = new StringBuffer();
				subLine.append(temps.peekLast()+" = ");
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
				subLine.delete(0, subLine.length());
			} else if (isOperation(tokens[i])) {
				// an operation
				System.out.println("\toperation");
				
				switch(getOperation(tokens[i])) {
				case ADD: operation = "add"; break;
				case SUB: operation = "sub"; break;
				case MUL: operation = "mul"; break;
				case DIV: operation = "div"; break;
				case GOTO: operation = "j"; break;
				default: throw new StringNotFoundException("No operation found.");
				}
			} else {
				System.out.println("\tunknown");
			}
		}
		
		// create the output line
		if (!operands.isEmpty()) {
			oper1 = varToISAVar(operands.poll());
		}
		if (!operands.isEmpty()) {
			oper2 = varToISAVar(operands.poll());
		}
		
		writeLine(operation, result, oper1, oper2);
		
		// cleanup
		while (!temps.isEmpty()) {
			String var = ISAVarToVar(temps.pollLast());
			tempAddrs.remove(var);
		}
	}
	
	/** Writes the line to the output in the ISA language
	 * 
	 * @param operation The operation of the line
	 * @param operands	The operands for an assignment
	 */
	protected abstract void writeLine(String operation, String... operands);
	
	/** Returns the register that holds the variable var.
	 * If the input is already a register, returns var unchanged.
	 * 
	 * @param var The name of a variable to translate to a register
	 * @return The name of the register where the variable is stored
	 */
	protected String varToISAVar(char var) {
		return varToISAVar(String.valueOf(var));
	}
	
	/** Returns the ISA version of the variable. 
	 * In the case of LoadStore, it's the register that holds the variable var.
	 * If the input is already in ISA format, returns var unchanged.
	 * 
	 * @param var The name of a variable to translate to a register
	 * @return The name of the register where the variable is stored
	 */
	protected abstract String varToISAVar(String var);
	
	/** Translates the variable currently in ISA format back to its C-like format
	 * eg. In LoadStore, the variable that is held in the register reg.
	 * If the input is already a variable, returns reg unchanged.
	 * 
	 * @param reg The name of a register to translate to a variable
	 * @return The name of the variable held in reg
	 */
	protected abstract String ISAVarToVar(String isaVar);
	
	/** Prepends the current line of code with the lines to initialize an array
	 * 
	 * @param token	The array name and index, eg. "A[I]"
	 * @param tempName The name of the temporary address that will replace the array name in the current line 
	 * @return the new programCounter after adding the lines
	 */
	protected abstract void addArrayLoadingLine(String token, String tempName);
	
	/** Parses, translates, and appends the if statement into ISA code
	 * 
	 * @param line An if statement
	 * @throws StringNotFoundException 
	 */
	protected abstract void handleIfStatement(String line) throws StringNotFoundException;
	
	/** Parses, translates, and appends the while statement into ISA code
	 * 
	 * @param line A while statement
	 * @throws StringNotFoundException 
	 */
	protected abstract void handleWhileStatement(String line) throws StringNotFoundException;
	
	/** Parses, translates, and appends the switch statement into ISA code
	 * 
	 * @param line A switch statement
	 * @throws StringNotFoundException 
	 */
	protected abstract void handleSwitchStatement(String line) throws StringNotFoundException;
	
	/** Parses, translates, and appends the function call into ISA code
	 * 
	 * @param line A function call
	 * @throws StringNotFoundException 
	 */
	protected abstract void handleFunctionCall(String line) throws StringNotFoundException;
	
	/** Parses, translates, and appends the function declaration and the function's code into ISA code
	 * 
	 * @param line A function declaration, including the body of the function
	 * @throws StringNotFoundException 
	 */
	protected abstract void handleFunctionDeclaration(String line) throws StringNotFoundException;
	
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
	
	/** Returns the name of the "return value" variable in ISA code to the output.
	 * Eg. in LoadStore it would be $v0, or in MM 4 Address it would be returnValue 
	 * 
	 */
	protected abstract String getReturnValueName();
	
	/** Returns the name of the "return value" variable in ISA code to the output.
	 * Eg. in LoadStore it would be $ra, or in MM 4 Address it would be returnAddress 
	 * 
	 */
	protected abstract String getReturnAddressName();
	
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
		labels.add(jumpLabels.peekLast());
		translateAndAppendLine(subCode.substring(5, elseStop)); // +5 to start after "else "
		writeLine("j", jumpLabels.peekLast());
		
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
	
	/** Returns true if the word is a label in the ISA code.
	 * Labels are recognized by being stored in the labels variable.
	 * 
	 * @param word
	 * @return
	 */
	protected boolean isLabel(String word) {
		return labels.contains(word);
	}
	
	protected boolean isIdentifier(String word) {
		return Arrays.asList(IDENTIFIERS).contains(word.toLowerCase());
	}
	
	/** Returns the name of a temporary address the compiler can use for an extra variable
	 * 
	 * @return
	 */
	protected abstract String getTempAddr();
}
