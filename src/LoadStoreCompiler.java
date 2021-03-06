import java.util.HashMap;
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
	
	protected void clear() {
		super.clear();
		sRegisters.clear();
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
		} else if (currentArgs.contains(var)) {
			return "$a"+currentArgs.indexOf(var);
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
		} else if (parts[1].matches("a")) {
			return currentArgs.get(Integer.parseInt(parts[2]));
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
		boolean nextIsArg = false;
		
		for (String w : words) {
			if (w.toLowerCase().contentEquals("goto")) {
				// this line is just a goto
				break;
			} else if (nextIsArg && w.matches("\\w")) {
				nextIsArg = false;
				String areg = "$a"+currentArgs.size();
				writeLine("lw", areg, w+"($zero)");
				currentArgs.add(w);
			} else if (isKeyword(w)) {
				continue;
			} else if (isIdentifier(w)) {
				nextIsArg = true;
			} else if (w.matches("[a-zA-Z]") && !sRegisters.contains(w) && !currentArgs.contains(w)) { 
				vars.add(w); 
			}
		}
		
		for (String v : vars) {
			String sreg = "$s"+sRegisters.size();
			writeLine("lw", sreg, v+"($zero)");
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
		StringBuffer toWrite = new StringBuffer();
		if (!labelsToPrepend.isEmpty()) { // add a label if we have one saved
			toWrite.append(labelsToPrepend.pollLast()+":\t");
		} else {
			toWrite.append("\t");
		}

		programBits += 6; // 6 opcode
		toWrite.append(operation+" ");
		
		for (int i=0; i<operands.length; i++) {
			if (!isLabel(operands[i])) {
				memAccesses++;
			}
			programBits += 24;
			if (i > 0) {
				toWrite.append(", ");
			}
			toWrite.append(operands[i]);
		}
		
		toWrite.append("\n");
		numInstructions++;
		
		if (insideFunctionDeclaration) {
			functionsToAdd.append(toWrite.toString());
		} else {
			output.append(toWrite);
		}
	}
	
	/** Prepends the current line of code with the lines to initialize an array
	 * 
	 * @param token	The array name and index, eg. "A[I]"
	 * @param tempName The name of the temporary address that will replace the array name in the current line 
	 * @return the new programCounter after adding the lines
	 */
	protected void addArrayLoadingLine(String token, String tempName) {
		String[] tokenParts = token.split("[\\[\\]]");
		String index = tokenParts[1], tokenReg = varToISAVar(tokenParts[0]), tempReg;
		tempAddrs.add(token);
		
		if (index.matches("-?\\d+")) {
			// index is an integer
			writeLine("lw", tempName, String.valueOf(4 * Integer.parseInt(index))+"("+tokenReg+")");
		} else {
			// index is a variable
			String indexReg = varToISAVar(tokenParts[1]);
			tempReg = getTempAddr();
			writeLine("load", tempReg, indexReg);
			writeLine("add", tempReg, tempReg);
			writeLine("add", tempReg, tempReg);
			writeLine("add", tempReg, tokenReg);
			writeLine("lw", tempName, "$zero("+tempReg+")");
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
			labels.add(ifLabels.peekLast());
		}
		
		switch (getIfCondition(part1)) {
		case EQ: // equal
			writeLine("beq", oper1, oper2); 
			writeLine("j", label); break;
		case NE: // not equal  
			writeLine("bne", oper1, oper2);  
			writeLine("j", label); break;
		case LE: // less than  
			temp = getTempAddr();
			writeLine("load", temp, oper1);
			writeLine("slt", temp, oper2);
			writeLine("bne", temp, "$zero");  
			writeLine("j", label); break;
		}
		
		handleElse(line);
		
		if (!ifLabels.isEmpty()) {
			labelsToPrepend.add(ifLabels.pollLast());
		}
		if (!hasGoto) {
			translateAndAppendLine(part2);
		}
		if (!jumpLabels.isEmpty()) {
			labelsToPrepend.add(jumpLabels.pollLast());
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
		labels.add(labelsToPrepend.peekLast());
		
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
			labels.add(jumpLabels.peekLast());
		}
		
		switch (getIfCondition(part1)) { 
		// basically, we do the opposite operation of an if statement, 
		// because failing the original condition breaks the loop 
		// (so succeeding the opposite jumps to outside the loop)
		case EQ: // equal
			writeLine("bne", oper1, oper2); 
			writeLine("j", label); break;
		case NE: // not equal  
			writeLine("beq", oper1, oper2); 
			writeLine("j", label); break;
		case LE: // less than
			String temp = getTempAddr();
			writeLine("load", temp, oper1);
			writeLine("slt", temp, oper2);
			writeLine("beq", temp, "$zero");  
			writeLine("j", label); break;
		}

		if (!hasGoto) {
			translateAndAppendLine(part2);
		}
		if (!jumpLabels.isEmpty()) {
			labelsToPrepend.add(jumpLabels.pollLast());
		}
	}
	
	/** Parses, translates, and appends the switch statement into ISA code
	 * Currently only parses switch statements for integers
	 * 
	 * @param line A switch statement
	 * @throws StringNotFoundException 
	 */
	protected void handleSwitchStatement(String line) throws StringNotFoundException {
		// count the number of cases
		String[] cases = line.split("case |default");
		int numCases = cases.length;
		if (!cases[cases.length-1].contains("default")) {
			numCases--;
		}
		
		// add the lines for the switch variable
		String switchVar = line.substring(line.indexOf('(')+1, line.indexOf(')'));
		String tempAddr = getTempAddr(), exitLabel = "Exit"+jumpLabels.size(); 
		jumpLabels.add(exitLabel);
		labels.add(jumpLabels.peekLast());
		writeLine("load", tempAddr, switchVar);
		writeLine("slti", tempAddr, switchVar);
		writeLine("bne", tempAddr, "$zero");
		writeLine("j", exitLabel);
		writeLine("load", tempAddr, switchVar);
		writeLine("slti", tempAddr, String.valueOf(numCases));
		writeLine("beq", tempAddr, "$zero");
		writeLine("j", exitLabel);
		writeLine("load", tempAddr, switchVar);
		writeLine("add", tempAddr, tempAddr);
		writeLine("add", tempAddr, tempAddr);
		String sreg = "$s"+sRegisters.size();
		writeLine("lw", sreg, "addrJumpTable($zero)");
		sRegisters.add("addrJumpTable");
		writeLine("add", tempAddr, sreg);
		writeLine("lw", tempAddr, "$zero("+tempAddr+")");
		writeLine("jr", tempAddr);
		
		int i = 0;
		for (String caseLine : cases) {
			if (caseLine.contains("switch")) {
				continue;
			}
			caseLine = caseLine.substring(caseLine.indexOf(":")+1, caseLine.indexOf("break;"));
			labelsToPrepend.add("L"+i++);
			translateAndAppendLine(caseLine);
			if (i < numCases) { // don't append the last jump, because we already go to the exit
				writeLine("j", exitLabel);
			}
			
		}
		
		if (!jumpLabels.isEmpty()) {
			labelsToPrepend.add(jumpLabels.pollLast());
		}
	}
	
	/** Parses, translates, and appends the function call into ISA code
	 * 
	 * @param line A function call
	 * @throws StringNotFoundException 
	 */
	protected void handleFunctionCall(String line) throws StringNotFoundException {
		if (insideFunctionDeclaration) {
			// store local variables
			writeLine("subi", getStackPointerName(), getStackPointerName(), "12");
			int i = 0;
			for (String a : currentArgs) {
				writeLine("sw", varToISAVar(a), "$ "+(4*i)+"("+getStackPointerName()+")");
				i++;
				stack.add(a);
			}
			writeLine("sw", getReturnAddressName(), "$ "+(4*i)+"("+getStackPointerName()+")");
			stack.add(getReturnAddressName());
			returns.add(getReturnAddressName());
		}
		
		// Get function name
		String[] tokens = line.split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
		String prev = "", name = "", argLabel;
		for (String t : tokens) {
			if (prev.length() > 0 && t.contentEquals("(")) {
				name = prev;
				break;
			}
			prev = t;
		}
		functions.put(name, new LinkedList<String>());
		
		// Get the arguments and replace them with "$a0", "$a1" etc
		String[] argsPart = line.substring(line.indexOf("(")+1, line.indexOf(")")).split("[,\\s]");
		for (String a : argsPart) {
			if (a.isEmpty()) {
				continue;
			}
			argLabel = "$a"+functions.get(name).size();
			functions.get(name).add(a);
			if (!argLabel.contentEquals(varToISAVar(a))) { // unless they already match
				writeLine("addi", argLabel, varToISAVar(a), "$zero");
			}
		}
		// add the function call in ISA code
		writeLine("jal", name);
		if (insideFunctionDeclaration) {
			// load the stored local variables after function call
			returns.poll();
			for (String s : stack) {
				writeLine("lw", varToISAVar(s), "$ "+(4*stack.indexOf(s))+"("+getStackPointerName()+")");
			}
		}
	}
	
	/** Parses, translates, and appends the function declaration and the function's code into ISA code
	 * 
	 * @param line A function declaration, including the body of the function
	 * @throws StringNotFoundException 
	 */
	protected void handleFunctionDeclaration(String line) throws StringNotFoundException {
		insideFunctionDeclaration = true;
		
		// add function name to first line as a label
		String[] tokens = line.split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
		String prev = "", name = "";
		for (String t : tokens) {
			if (prev.length() > 0 && t.contentEquals("(")) {
				name = prev;
				break;
			}
			prev = t;
		}
		labelsToPrepend.add(name);
		
		// replace argument names with addresses
		HashMap<String, String> argReplacements = new HashMap<String, String>();
		LinkedList<String> args = new LinkedList<String>();
		String[] argsPart = line.substring(line.indexOf("(")+1, line.indexOf(")")).split("[,\\s]");
		int argsNum = 0; 
		for (String a : argsPart) {
			if (a.isEmpty() || isIdentifier(a) || currentArgs.contains(a)) {
				continue;
			}
			args.add(a);
			String replacement = "$a"+(argsNum++);
			argReplacements.put(a, replacement);
			currentArgs.add(replacement);
		}
		String functionBody = line.substring(line.indexOf("{")+1, line.indexOf("}"));
		for (String arg : argReplacements.keySet()) {
			// replace eg. "x" by "$s0" in every instance of "x"
			functionBody = functionBody.replaceAll(arg.replaceAll("$",  "\\$"), argReplacements.get(arg).replaceAll("$", "\\$"));
		}
		
		String[] bodyLines = functionBody.split("(?<=;)");
		for (String b : bodyLines) {
			translateAndAppendLine(b);
		}
		
		// go back to old function
		if (stack.size() > 0) {
			writeLine("addi", getStackPointerName(), getStackPointerName(), String.valueOf(4*stack.size()));
			stack.removeAll(stack);
		}
		writeLine("jr", getReturnAddressName());
		insideFunctionDeclaration = false;
		currentArgs.clear();
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
	
	/** Returns the name of the stack pointer variable in ISA code to the output.
	 * Eg. in LoadStore it would be $sp 
	 * 
	 */
	protected String getStackPointerName() {
		return "$sp";
	}
	
	/** Returns the operands in parenthesis to be compared in a while/if statement
	 * 
	 * @param line a line containing a while/if statement and a condition in parenthesis
	 * @return the operands to compare
	 */
	protected LinkedList<String> getOperandsToCompare(String line) {
		String[] tokens = line.split("\\s|(?=[-+*/()=:;])|(?<=[^-+*/=:;][-+*/=:;])|(?<=[()])");
		LinkedList<String> operands = new LinkedList<String>(), temps = new LinkedList<String>();
		
		// for each token in the line, check if it is a variable
		for (int i=0; i<tokens.length; i++) {
			if (tokens[i].length() > 1) {
				// a word, presumably array
				if (tokens[i].contains("[")) {
					// an array index
					temps.add(getTempAddr()); 
					tempAddrs.add(tokens[i]);
					
					// add lines to load the indexed value
					addArrayLoadingLine(tokens[i], temps.peekLast());
					operands.add(temps.peekLast());
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
	
	/** Adds a store command in ISA code for the given variable 
	 * 
	 */
	protected void store(String word) {
		if (!word.contentEquals(getReturnValueName())) {
			writeLine("store", ISAVarToVar(word), varToISAVar(word));
		}
	}
	
	/** Adds a jump command in ISA code for the given address/label
	 * 
	 * @param address
	 */
	protected void jump(String address) {
		writeLine("j", jumpLabels.peekLast());
	}
	
	/** Adds a return address label to the line if needed by the architecture
	 * 
	 */
	protected void addReturnAddressLabel() {
		// we don't need this because we already have $ra embedded
	}
	
	/** Loads the result of the operation in the return value
	 * Note: operands should have exactly 1 value
	 */
	protected void setReturnValue(String result, LinkedList<String> operands) {
		writeLine("add", result, operands.poll(), "$zero");
	}
	
	/** Adds a line for result = operand statements
	 * 
	 * @param result
	 * @param operand
	 */
	protected void addOneOperLine(String result, String operand) {
		writeLine("add", result, operand, "$zero");
	}
}
