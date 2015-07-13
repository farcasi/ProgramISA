import java.util.LinkedList;



/** Each instruction is 8 + 24 x 4 = 104 bits, or 13 bytes
 * 
 * Format:
 * instr resultAddr, oper1Addr, oper2Addr, nextInstrAddr
 * 
 */
public class MM4AddressCompiler extends Compiler {
	
	public MM4AddressCompiler() {
		instructionSize = 13;
	}
	
	/** Translates C-like code into assembly code. 
	 * 
	 * @param code A string of code written in a C-like language
	 * @return The translation of the input into assembly code
	 */
	public String compile(String code) throws StringNotFoundException {
		String fullCode = code.replaceAll("\n", "");
		
		// assign registers to variables
		loadVars(code);
		output.append("Variables: ");
		if (sRegisters.size() > 0) {
			output.append(sRegisters.get(0)+": "+varToReg(sRegisters.get(0)));
			if (sRegisters.size() > 1) {
				for (int i=1, tot=sRegisters.size(); i<tot; i++) {
					output.append(", "+sRegisters.get(i)+": "+varToReg(sRegisters.get(i)));
				}
			}
			output.append("\nCode:\n");
		}
		
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
		
		return output.toString();
	}
	
	/** Translates a line to 4-address assembly code and returns the result
	 * 
	 * Note: This method is gigantic, but I'm not sure how to shorten/compartmentalize it
	 * 
	 * @param line	the line to be translated
	 * @param programCounter	the address of the line
	 * @return	the line in ISA code
	 */
	private void translateAndAppendLine(String line) throws StringNotFoundException {
		StringBuffer temp = new StringBuffer();
		String operation = "", result = "", oper1 = "", oper2 = "";
		LinkedList<String> operands = new LinkedList<String>(), treg = new LinkedList<String>();
		boolean passedAssignmentOperator = false;
		
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
			if (tokens[i].contentEquals("(")) {
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
					operation = "beq";
					StringBuffer subLine = new StringBuffer();
					int subi = i+1;
					boolean insideIf = false;
					
					for (; subi < tokens.length && !tokens[subi].contentEquals(")"); subi++) {
						// move the code inside parenthesis to a subLine
						if (tokens[subi-1].contentEquals("(")) {
							insideIf = true;
						}
						if (insideIf) {
							subLine.append(tokens[subi]);
							tokens[subi] = " ";
						}
					}
					
					if (subi < tokens.length) {
						subLine.append(tokens[subi]);
						tokens[subi] = " "; // erase the close-parens
					}
					
					handleIfStatement(subLine.toString());				
					subLine.delete(0, subLine.length());
				} else if (tokens[i].contentEquals("while")) {
					// TODO: do something
					// add loop point, condition, and (later in the code) a jump
				} else if (tokens[i].contains("[")) {
					// an array index
					treg.add("$t"+tRegisters.size()); 
					tRegisters.add(tokens[i]);
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
		
		if (!labelToPrepend.isEmpty()) { // add a label if we have one saved
			output.append(labelToPrepend.pollLast()+": ");
		}
		if (operation.matches("j")) { // choose the output format based on operation
			output.append(operation+" "+result);
		} else if (!jumpLabels.isEmpty()) {
			output.append(operation+" "+result+", "+oper1+", "+oper2+", "+jumpLabels.pollLast()+"\n");
		} else {
			output.append(operation+" "+result+", "+oper1+", "+oper2+", "+(programCounter+instructionSize)+"\n");
		}
		
		// cleanup
		while (!treg.isEmpty()) {
			String var = regToVar(treg.pollLast());
			tRegisters.remove(var);
		}
	}
	
	/** Returns the register that holds the variable var.
	 * If the input is already a register, returns var unchanged.
	 * 
	 * @param var The name of a variable to translate to a register
	 * @return The name of the register where the variable is stored
	 */
	private String varToReg(char var) {
		return varToReg(String.valueOf(var));
	}
	
	/** Returns the register that holds the variable var.
	 * If the input is already a register, returns var unchanged.
	 * 
	 * @param var The name of a variable to translate to a register
	 * @return The name of the register where the variable is stored
	 */
	private String varToReg(String var) {
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
	private String regToVar(String reg) {
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
	private void addArrayLoadingLine(String token, String treg) {
		int istart = token.indexOf('[')+1, iend = token.indexOf(']');
		String index = token.substring(istart, iend), tokenReg = varToReg(token.charAt(0)), tempReg;
		if (index.matches("-?\\d+")) {
			// index is an integer
			output.append("lw "+treg+", "+(4 * Integer.parseInt(index))+"("+tokenReg+"), "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
		} else {
			// index is a variable
			String indexReg = varToReg(token.charAt(2));
			tempReg = "$t"+tRegisters.size();
			output.append("add "+tempReg+", "+tokenReg+", "+indexReg+", "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
			output.append("add "+tempReg+", "+tempReg+", "+tempReg+", "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
			output.append("add "+tempReg+", "+tempReg+", "+tokenReg+", "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
			output.append("lw "+treg+", 0("+tempReg+"), "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
		}
	}
	
	/** Parses, translates, and appends the if statement into ISA code
	 * 
	 * @param line An if statement
	 */
	private void handleIfStatement(String line) {
		
	}
}
