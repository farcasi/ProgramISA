import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;



/** General format:
 * push operAddr
 * instr 
 * pop operAddr
 * 
 * With every instruction making an implicit memory access to the accumulator
 * 
 */
public class StackCompiler extends Compiler {
	
	/** Returns the ISA version of the variable. 
	 * In the case of MM4Address, it's just var.
	 * If the input is already in ISA format, returns var unchanged.
	 * 
	 * @param var The name of a variable to translate to a register
	 * @return The name of the register where the variable is stored
	 */
	protected String varToISAVar(String var) {
		return var;
	}
	
	/** Translates the variable currently in ISA format back to its C-like format
	 * eg. In LoadStore, the variable that is held in the register reg.
	 * If the input is already a variable, returns reg unchanged.
	 * 
	 * @param reg The name of a register to translate to a variable
	 * @return The name of the variable held in reg
	 */
	protected String ISAVarToVar(String isaVar) {
		int addrLoc = isaVar.indexOf("Temp");
		if (addrLoc > -1) {
			return isaVar.substring(addrLoc+4);
		}
		
		return isaVar;
	}
	
	/** Gets the variables in a line of code and loads them as appropriate for the compiler 
	 * Variables are identified by being single, capital-letter characters.
	 * 
	 * @param code C-like code
	 */
	protected void loadVars(String line) {
		String[] words = line.split("\\s+|(?<=\\W)(?=\\w)|\\s+|(?<=\\w)(?=\\W)|\\s+");
		vars = new HashSet<String>();
		boolean nextIsArg = false, insideParens = false;
		
		for (String w : words) {
			if (w.toLowerCase().contentEquals("goto")) {
				// this line is just a goto
				break;
			} else if (nextIsArg && insideParens && w.matches("\\w+")) {
				nextIsArg = false;
				String areg = "arg"+currentArgs.size();
				load(w);
				store(areg);
				currentArgs.add(w);
			} else if (isKeyword(w)) {
				continue;
			} else if (insideParens && isIdentifier(w)) {
				nextIsArg = true;
			} else if (w.contentEquals("(")) {
				insideParens = true;
			} else if (w.contentEquals(")")) {
				insideParens = false;
			} else if (w.matches("[a-zA-Z]+")) { 
				vars.add(w); 
			}
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
		
		// add label
		if (!labelsToPrepend.isEmpty()) { // add a label if we have one saved
			toWrite.append(labelsToPrepend.pollLast()+":\t");
		} else {
			toWrite.append("\t");
		}
		
		// reduce the line to 0 addresses
		String result = "";
		LinkedList<String> operandsList = new LinkedList<String>();
		operandsList.addAll(Arrays.asList(operands));
		// regular input is like "add", "a", "b", "c" for a = b + c, so fix that
		// first get the result of an assignment, if there is one
		if (operation.matches("[\\-*+\\/]")) {
			result = operandsList.poll();
		}
		// now remove operands 
		while (operandsList.size() > 0) {
			// remove extra addresses
			toWrite.append("push "+operandsList.poll()+"\n\t");
			programBits += 30;
			numInstructions++;
			memAccesses++;
		}

		programBits += 6; // 6 opcode
		toWrite.append(operation+"\n");
		numInstructions++;
		
		if (!result.isEmpty()) {
			store(result);
		}
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
		String index = tokenParts[1], tokenReg = varToISAVar(tokenParts[0]);
		
		if (index.matches("-?\\d+")) {
			// index is an integer
			load(String.valueOf(4 * Integer.parseInt(index))+"("+tokenReg+")");
			writeLine("lw");
		} else {
			// index is a variable
			String indexReg = varToISAVar(tokenParts[1]);
			load(indexReg);
			load("4");
			writeLine("muli");
			load(tokenReg);
			writeLine("add");
			writeLine("lw");
			store(tempName);
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
			label = "True"+ifLabels.size(); 
			ifLabels.add(label);
			labels.add(ifLabels.peekLast());
		}
		
		switch (getIfCondition(part1)) { 
		case EQ: // equal
			load(label); // assuming beq compares the top 2 elements of the stack, and if they're the same grabs the next one down
			load(oper1);
			load(oper2);
			writeLine("beq"); break;
		case NE: // not equal 
			load(label); 
			load(oper1);
			load(oper2);
			writeLine("bne"); break;
		case LE: // less than
			load(label); 
			load(oper1);
			load(oper2);
			writeLine("slt"); 
			load("0");
			writeLine("bne"); break;
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
			load(label);
			load(oper1);
			load(oper2);
			writeLine("bne"); break;
		case NE: // not equal
			load(label);
			load(oper1);
			load(oper2);
			writeLine("beq"); break;
		case LE: // less than
			load(label); 
			load(oper1);
			load(oper2);
			writeLine("slt"); 
			load("0");
			writeLine("beq"); break;
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
		String exitLabel = "Exit"+jumpLabels.size(); 
		jumpLabels.add(exitLabel);
		labels.add(jumpLabels.peekLast());
		load(exitLabel);
		load(switchVar);
		load("0");
		writeLine("slti");
		load("0");
		writeLine("bne");
		load(exitLabel);
		load(switchVar);
		load(String.valueOf(numCases));
		writeLine("slti");
		load("0");
		writeLine("beq");
		load(switchVar);
		load("4");
		writeLine("mul");
		load("addrJumpTable");
		writeLine("add");
		writeLine("lw");
		writeLine("jr");
		
		int i = 0;
		for (String caseLine : cases) {
			if (caseLine.contains("switch")) {
				continue;
			}
			caseLine = caseLine.substring(caseLine.indexOf(":")+1, caseLine.indexOf("break;"));
			labelsToPrepend.add("L"+i++);
			translateAndAppendLine(caseLine);
			if (i < numCases) { // don't append the last jump, because we already go to the exit
				load(exitLabel);
				writeLine("j");
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
			int i=0;
			load(getReturnAddressName());
			store("stackAddr"+i);
			stack.add(getReturnAddressName());
			for (String a : currentArgs) {
				i++;
				load(a);
				store("stackAddr"+i);
				stack.add(a);
			}
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
		
		// Get the arguments and replace them with "arg0", "arg1" etc
		String[] argsPart = line.substring(line.indexOf("(")+1, line.indexOf(")")).split("[,\\s]");
		for (String a : argsPart) {
			if (a.isEmpty()) {
				continue;
			}
			argLabel = "arg"+functions.get(name).size();
			functions.get(name).add(a);
			if (!argLabel.matches(a)) { // unless they already match
				load(a);
				store(argLabel);
			}
		}
		load(name);
		writeLine("jal");
		jumpLabels.add(getReturnAddressName());
		if (insideFunctionDeclaration) {
			labelsToPrepend.add(jumpLabels.pollLast());
			// load the stored local variables
			returns.add(getReturnAddressName());
			for (String s : stack) {
				load("stackAddr"+stack.indexOf(s));
				store(s);
			}
			stack.removeAll(stack);
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
			if (a.isEmpty() || isIdentifier(a)) {
				continue;
			}
			args.add(a);
			String replacement = "arg"+(argsNum++);
			argReplacements.put(a, replacement);
			currentArgs.add(replacement);
		}
		String functionBody = line.substring(line.indexOf("{")+1, line.indexOf("}"));
		for (String arg : argReplacements.keySet()) {
			// replace eg. "x" by "$s0" in every instance of "x"
			functionBody = functionBody.replaceAll(arg, argReplacements.get(arg));
		}
		
		String[] bodyLines = functionBody.split("(?<=;)");
		for (String b : bodyLines) {
			translateAndAppendLine(b);
		}
		
		load(getReturnAddressName());
		writeLine("jr");
		insideFunctionDeclaration = false;
		currentArgs.clear();
	}
	
	/** Adds the name of the "return value" variable in ISA code to the output.
	 * Eg. in LoadStore it would be $v0, or in MM 4 Address it would be returnValue 
	 * 
	 */
	protected String getReturnValueName() {
		return "returnValue";
	}
	
	/** Returns the name of the "return value" variable in ISA code to the output.
	 * Eg. in LoadStore it would be $ra, or in MM 4 Address it would be returnAddress 
	 * 
	 */
	protected String getReturnAddressName() {
		return "returnAddress"+returns.size();
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
		return "Temp"+tempAddrs.size();
	}
	
	/** Adds a store command in ISA code for the given variable 
	 * 
	 */
	protected void store(String word) {
		StringBuffer toWrite = new StringBuffer();
		programBits += 30; // 6 opcode + 24 address
		numInstructions++;
		memAccesses++;
		// add label
		if (!labelsToPrepend.isEmpty()) { // add a label if we have one saved
			toWrite.append(labelsToPrepend.pollLast()+":\t");
		} else {
			toWrite.append("\t");
		}
		toWrite.append("pop "+word+"\n");
		if (insideFunctionDeclaration) {
			functionsToAdd.append(toWrite);
		} else {
			output.append(toWrite);
		}
	}
	
	/** Adds a command to push the variable at address onto the stack
	 *  
	 * @param address
	 */
	protected void load(String address) {
		StringBuffer toWrite = new StringBuffer();
		programBits += 30; // 6 opcode + 24 address
		numInstructions++;
		memAccesses++;
		// add label
		if (!labelsToPrepend.isEmpty()) { // add a label if we have one saved
			toWrite.append(labelsToPrepend.pollLast()+":\t");
		} else {
			toWrite.append("\t");
		}
		toWrite.append("push "+address+"\n");
		if (insideFunctionDeclaration) {
			functionsToAdd.append(toWrite);
		} else {
			output.append(toWrite);
		}
	}
	
	/** Adds a jump command in ISA code for the given address/label
	 * 
	 * @param address
	 */
	protected void jump(String address) {
		load(jumpLabels.peekLast());
		writeLine("j");
	}
	
	/** Adds a return address label to the line if needed by the architecture
	 * 
	 */
	protected void addReturnAddressLabel() {
		jumpLabels.add(getReturnAddressName());
	}
	
	/** Loads the result of the operation in the return value
	 * Note: operands should have exactly 1 value
	 */
	protected void setReturnValue(String result, LinkedList<String> operands) {
		load(operands.poll());
		store(result);
	}
	
	/** Adds a line for result = operand statements
	 * 
	 * @param result
	 * @param operand
	 */
	protected void addOneOperLine(String result, String operand) {
		load(operand);
		store(result);
	}
}
