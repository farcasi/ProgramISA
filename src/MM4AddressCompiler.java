import java.util.Arrays;
import java.util.HashSet;
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
			output.append("\tlw "+sreg+", addr"+v+"($zero), "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
			sRegisters.add(v);
		}
		vars.clear();
	}
	
	protected void writeLine(String operation, String result, String oper1, String oper2) {
		if (!labelToPrepend.isEmpty()) { // add a label if we have one saved
			output.append(labelToPrepend.pollLast()+":\t");
		} else {
			output.append("\t");
		}
		if (operation.matches("j")) { // choose the output format based on operation
			output.append(operation+" "+result);
		} else {
			output.append(operation+" "+result+", "+oper1+", "+oper2+", "+(programCounter+instructionSize)+"\n");
		}
	}
	
	/** Prepends the current line of code with the lines to initialize an array
	 * 
	 * @param token	The array name and index, eg. "A[I]"
	 * @param treg The name of the tregister that will replace the array name in the current line 
	 * @return the new programCounter after adding the lines
	 */
	protected void addArrayLoadingLine(String token, String treg) {
		String[] tokenParts = token.split("[\\[\\]]");
		String index = tokenParts[1], tokenReg = varToReg(tokenParts[0]), tempReg;
		tRegisters.add(token);
		
		if (!labelToPrepend.isEmpty()) { // add a label if we have one saved
			output.append(labelToPrepend.pollLast()+":\t");
		} else {
			output.append("\t");
		}
		
		if (index.matches("-?\\d+")) {
			// index is an integer
			output.append("lw "+treg+", "+(4 * Integer.parseInt(index))+"("+tokenReg+"), "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
		} else {
			// index is a variable
			String indexReg = varToReg(tokenParts[1]);
			tempReg = "$t"+tRegisters.size();
			output.append("add "+tempReg+", "+indexReg+", "+indexReg+", "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
			output.append("\tadd "+tempReg+", "+tempReg+", "+tempReg+", "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
			output.append("\tadd "+tempReg+", "+tempReg+", "+tokenReg+", "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize;
			output.append("\tlw "+treg+", 0("+tempReg+"), "+(programCounter+instructionSize)+"\n");
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
			output.append("\tbeq "+oper1+", "+oper2+", "+label+"\n");
			programCounter += instructionSize; break;
		case NE: // not equal  
			output.append("\tbne "+oper1+", "+oper2+", "+label+"\n");
			programCounter += instructionSize; break;
		case LE: // less than  
			temp = "$t"+tRegisters.size();
			output.append("\tslt "+temp+", "+oper1+", "+oper2+", "+(programCounter+instructionSize)+"\n");
			programCounter += instructionSize; 
			output.append("\tbne "+temp+", $zero, "+label+"\n"); 
			programCounter += instructionSize; break;
		}
		
		handleElse(line);

		if (!jumpLabels.isEmpty()) {
			labelToPrepend.add(jumpLabels.pollLast());
		}
		if (!ifLabels.isEmpty()) {
			labelToPrepend.add(ifLabels.pollLast());
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
		String loopLabel = "Loop"+labelToPrepend.size();
		labelToPrepend.add(loopLabel);
		
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
			output.append("\tbne "+oper1+", "+oper2+", "+label+"\n");
			programCounter += instructionSize; break;
		case NE: // not equal  
			output.append("\tbeq "+oper1+", "+oper2+", "+label+"\n");
			programCounter += instructionSize; break;
		case LE: // less than  
			// implement when I know how to do both "greater than" and "equal" in the same condition
		}

		if (!hasGoto) {
			translateAndAppendLine(part2);
		}
		if (!jumpLabels.isEmpty()) {
			labelToPrepend.add(jumpLabels.pollLast());
		}
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
					treg.add("$t"+tRegisters.size()); 
					tRegisters.add(tokens[i]);
					
					// add lines to load the indexed value into the tregister
					addArrayLoadingLine(tokens[i], treg.peekLast());
					operands.add(varToReg(tokens[i]));
				} else if (tokens[i].charAt(0) == '=') {
					// we have '=var', fix it and try again
					tokens[i] = tokens[i].substring(1);
					i--;
				} else {
					operands.add(varToReg(tokens[i]));
				}
			} else if (tokens[i].length() > 0 && Character.isLetter(tokens[i].charAt(0))) {
				// a letter (a variable)
				operands.add(varToReg(tokens[i]));
			} 
		}
		return operands;
	}
}
