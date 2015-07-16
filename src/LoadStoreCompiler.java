import java.util.HashSet;
import java.util.LinkedList;



/** General format:
 * lw resultAddr
 * lw oper1Addr
 * lw oper2Addr
 * instr resultAddr, oper1Addr, oper2Addr, nextInstrAddr
 * sw resultAddr
 * 
 */
public class LoadStoreCompiler extends Compiler {
	
	protected LinkedList<String> sRegisters = new LinkedList<String>();
	
	public LoadStoreCompiler() {
		instructionSize = 2;
	}
	
	protected void clear() {
		super.clear();
		sRegisters.clear();
	}
	
	/** Adds the name of the "return value" variable in ISA code to the output.
	 * Eg. in LoadStore it would be $v0, or in MM 4 Address it would be returnValue 
	 * 
	 */
	protected String getReturnValueName() {
		return "$v0";
	}

	/** Returns the name of the "return value" variable in ISA code to the output.
	 * Eg. in LoadStore it would be $ra, or in MM 4 Address it would be returnAddress 
	 * 
	 */
	protected String getReturnAddressName() {
		return "$ra";
	}
	
	/** Returns the ISA version of the variable. 
	 * In the case of LoadStore, it's the register that holds the variable var.
	 * If the input is already in ISA format, returns var unchanged.
	 * 
	 * @param var The name of a variable to translate to a register
	 * @return The name of the register where the variable is stored
	 */
	protected String varToISAVar(String var) {
		if (var.length() < 1 || var.charAt(0) == '$' || isNumeric(var)) {
			return var;
		} else if (tempAddrs.contains(var)) {
			return "$t"+tempAddrs.indexOf(var);
		} else if (sRegisters.contains(var)) {
			return "$s"+sRegisters.indexOf(var);
		} else {
			return var;
		}
	}
	
	/** Translates the variable currently in ISA format back to its C-like format
	 * eg. In LoadStore, the variable that is held in the register reg.
	 * If the input is already a variable, returns reg unchanged.
	 * 
	 * @param reg The name of a register to translate to a variable
	 * @return The name of the variable held in reg
	 */
	protected String ISAVarToVar(String isaVar) {
		String[] parts = isaVar.split("(?=[a-zA-Z])|(?<=[a-zA-Z])");
		if (isaVar.charAt(0) != '$') {
			return isaVar;
		} else if (parts[1].matches("t")) {
			return tempAddrs.get(Integer.parseInt(parts[2]));
		} else if (parts[1].matches("s")) {
			return sRegisters.get(Integer.parseInt(parts[2]));
		} else {
			return isaVar;
		}
	}
	
	/** Gets the variables in a line of code and loads them as appropriate for the compiler 
	 * Variables are identified by being single, capital-letter characters.
	 * 
	 * @param code C-like code
	 */
	protected void loadVars(String line) {
		String[] words = line.split("\\s+|(?<=\\W)(?=\\w)|\\s+|(?<=\\w)(?=\\W)|\\s+");
		vars = new HashSet<String>();
		
		for (String w : words) {
			if (w.toLowerCase().contentEquals("goto")) {
				// this line is just a goto
				break;
			} else if (isKeyword(w)) {
				continue;
			} else if (w.matches("[a-zA-Z]+") && !sRegisters.contains(w)) { 
				vars.add(w); // add the single-character variable
			}
		}
		
		for (String v : vars) {
			String sreg = "$s"+sRegisters.size();
			writeLine("lw", sreg, "addr"+v+"($zero)");
			programCounter += instructionSize;
			sRegisters.add(v);
		}
		vars.clear();
	}
	
	/** Writes the line to the output in the ISA language
	 * 
	 * @param operation The operation of the line
	 * @param oper	The operands for an assignment
	 */
	protected void writeLine(String operation, String... operands) {
		if (!labelsToPrepend.isEmpty()) { // add a label if we have one saved
			output.append(labelsToPrepend.pollLast()+":\t");
		} else {
			output.append("\t");
		}

		instructionSize += 4;
		programBits += 6; // 6 opcode
		output.append(operation+" ");
		
		for (int i=0; i<operands.length; i++) {
			if (!isLabel(operands[i])) {
				memAccesses++;
			}
			instructionSize += 3;
			programBits += 24;
			if (i > 0) {
				output.append(", ");
			}
			output.append(operands[i]);
		}
		
		if (!operation.matches("j|bne|beq")) { // choose the output format based on operation
			instructionSize += 3;
			programBits += 24;
			output.append(", "+(programCounter+instructionSize));
		}
		
		output.append("\n");
		programCounter += instructionSize;
		instructionSize = 0;
		numInstructions++;
	}
	
	/** Prepends the current line of code with the lines to initialize an array
	 * 
	 * @param token	The array name and index, eg. "A[I]"
	 * @param tempName The name of the tregister that will replace the array name in the current line 
	 * @return the new programCounter after adding the lines
	 */
	protected void addArrayLoadingLine(String token, String tempName) {
		String[] tokenParts = token.split("[\\[\\]]");
		String index = tokenParts[1], tokenReg = varToISAVar(tokenParts[0]), tempReg;
		tempAddrs.add(token);
		
		if (index.matches("-?\\d+")) {
			// index is an integer
			writeLine("lw", tempName, String.valueOf(4 * Integer.parseInt(index)), "("+tokenReg+")");
			programCounter += instructionSize;
		} else {
			// index is a variable
			String indexReg = varToISAVar(tokenParts[1]);
			tempReg = "$t"+tempAddrs.size();
			writeLine("add", tempReg, indexReg, indexReg);
			programCounter += instructionSize;
			writeLine("add", tempReg, tempReg, tempReg);
			programCounter += instructionSize;
			writeLine("add", tempReg, tempReg, tokenReg);
			programCounter += instructionSize;
			writeLine("lw", tempName, "0("+tempReg+")");
			programCounter += instructionSize;
		}
	}
	
	/** Parses, translates, and appends the if statement into ISA code
	 * 
	 * @param line An if statement
	 * @throws StringNotFoundException 
	 */
	protected void handleIfStatement(String line) throws StringNotFoundException {
		// get the variables we need to compare
		int conditionStart = line.indexOf("("), conditionEnd = line.indexOf(")");
		String part1 = line.substring(conditionStart, conditionEnd), part2 = line.substring(conditionEnd+1);
		LinkedList<String> operands = getOperandsToCompare(part1);
		
		String oper1 = operands.poll(), oper2 = operands.poll(), label = "", temp;
		boolean hasGoto = (getOperation(line) == Operation.GOTO)? true : false;
		if (hasGoto) {
			String[] tokens = part2.split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
			for (int i=0; i<tokens.length; i++) {
				if (tokens[i].length() > 1 && !tokens[i].toLowerCase().contentEquals("goto")) {
					// found our label
					label = tokens[i];
					break;
				}
			}
		} else {
			label = "True"+ifLabels.size(); 
			ifLabels.add(label);
		}
		
		switch (getIfCondition(part1)) {
		case EQ: // equal
			writeLine("beq", oper1, oper2, label);
			programCounter += instructionSize; break;
		case NE: // not equal  
			writeLine("bne", oper1, oper2, label);
			programCounter += instructionSize; break;
		case LE: // less than  
			temp = "$t"+tempAddrs.size();
			writeLine("slt", temp, oper1, oper2);
			programCounter += instructionSize; 
			writeLine("bne", temp, "$zero", label); 
			programCounter += instructionSize; break;
		}
		
		handleElse(line);

		if (!jumpLabels.isEmpty()) {
			labelsToPrepend.add(jumpLabels.pollLast());
		}
		if (!ifLabels.isEmpty()) {
			labelsToPrepend.add(ifLabels.pollLast());
		}
		if (!hasGoto) {
			translateAndAppendLine(part2);
		}
	}
	
	/** Parses, translates, and appends the while statement into ISA code
	 * 
	 * @param line A while statement
	 * @throws StringNotFoundException 
	 */
	protected void handleWhileStatement(String line) throws StringNotFoundException {
		// create a loop label
		String loopLabel = "Loop"+labelsToPrepend.size();
		labelsToPrepend.add(loopLabel);
		
		// get the variables we need to compare
		int conditionStart = line.indexOf("("), conditionEnd = line.indexOf(")");
		String part1 = line.substring(conditionStart, conditionEnd), part2 = line.substring(conditionEnd+1);
		LinkedList<String> operands = getOperandsToCompare(part1);
		
		String oper1 = operands.poll(), oper2 = operands.poll(), label = "";
		boolean hasGoto = (getOperation(line) == Operation.GOTO)? true : false;
		if (hasGoto) {
			String[] tokens = part2.split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
			for (int i=0; i<tokens.length; i++) {
				if (tokens[i].length() > 1 && !tokens[i].toLowerCase().contentEquals("goto")) {
					// found our label
					label = tokens[i];
					break;
				}
			}
		} else {
			label = "Exit"+jumpLabels.size(); 
			jumpLabels.add(label);
		}
		
		switch (getIfCondition(part1)) { 
		// basically, we do the opposite operation of an if statement, 
		// because failing the original condition breaks the loop 
		// (so succeeding the opposite jumps to outside the loop)
		case EQ: // equal
			writeLine("bne", oper1, oper2, label);
			programCounter += instructionSize; break;
		case NE: // not equal  
			writeLine("beq", oper1, oper2, label);
			programCounter += instructionSize; break;
		case LE: // less than  
			// implement when I know how to do both "greater than" and "equal" in the same condition
		}

		if (!hasGoto) {
			translateAndAppendLine(part2);
		}
		if (!jumpLabels.isEmpty()) {
			labelsToPrepend.add(jumpLabels.pollLast());
		}
	}
	
	/** Parses, translates, and appends the switch statement into ISA code
	 * 
	 * @param line A switch statement
	 * @throws StringNotFoundException 
	 */
	protected void handleSwitchStatement(String line) throws StringNotFoundException {
		
	}
	
	/** Parses, translates, and appends the function call into ISA code
	 * 
	 * @param line A function call
	 * @throws StringNotFoundException 
	 */
	protected void handleFunctionCall(String line) throws StringNotFoundException {
		
	}
	
	/** Parses, translates, and appends the function declaration and the function's code into ISA code
	 * 
	 * @param line A function declaration, including the body of the function
	 * @throws StringNotFoundException 
	 */
	protected void handleFunctionDeclaration(String line) throws StringNotFoundException {
		
	}
	
	
	protected LinkedList<String> getOperandsToCompare(String line) {
		String[] tokens = line.split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
		LinkedList<String> operands = new LinkedList<String>(), treg = new LinkedList<String>();
		
		// for each token in the line, check if it is a variable
		for (int i=0; i<tokens.length; i++) {
			if (tokens[i].length() > 1) {
				// a word, presumably array
				if (tokens[i].contains("[")) {
					// an array index
					treg.add("$t"+tempAddrs.size()); 
					tempAddrs.add(tokens[i]);
					
					// add lines to load the indexed value into the tregister
					addArrayLoadingLine(tokens[i], treg.peekLast());
					operands.add(varToISAVar(tokens[i]));
				} else if (tokens[i].charAt(0) == '=') {
					// we have '=var', fix it and try again
					tokens[i] = tokens[i].substring(1);
					i--;
				} else {
					operands.add(varToISAVar(tokens[i]));
				}
			} else if (tokens[i].length() > 0 && Character.isLetter(tokens[i].charAt(0))) {
				// a letter (a variable)
				operands.add(varToISAVar(tokens[i]));
			} 
		}
		return operands;
	}

	/** Returns the name of a temporary address the compiler can use for an extra variable
	 * 
	 * @return
	 */
	protected String getTempAddr() {
		return "$t"+tempAddrs.size();
	}
}
