package recordjsonproperty.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorActionDelegate;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;

public class RecordJsonPropertyHandler implements IEditorActionDelegate {

	private IEditorPart editor;

	@Override
	public void setActiveEditor(IAction action, IEditorPart targetEditor) {
		this.editor = targetEditor;
		if (action != null && editor != null) {
			action.setEnabled(isRecordPresent());
		}
	}

	@Override
	public void run(IAction action) {
		if (editor == null) {
			return;
		}

		try {
			IEditorInput input = editor.getEditorInput();
			if (!(input instanceof IFileEditorInput))
				return;

			IFileEditorInput fileInput = (IFileEditorInput) input;
			ICompilationUnit unit = JavaCore.createCompilationUnitFrom(fileInput.getFile());

			if (unit != null) {
				String source = unit.getSource();
				IType[] types = unit.getTypes();

				for (IType type : types) {
					if (type.isRecord()) {
						addAnnotationsToRecord(unit, source, type);
						break;
					}
				}
			}
		} catch (Exception e) {
			MessageDialog.openError(editor.getSite().getShell(), "Error",
					"Error adding annotations: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void addAnnotationsToRecord(ICompilationUnit unit, String source, IType type) throws Exception {
		int startPos = source.indexOf("record " + type.getElementName());
		if (startPos != -1) {
			int openParen = source.indexOf('(', startPos);
			int closeParen = findClosingParenthesis(source, openParen);
			if (openParen != -1 && closeParen != -1) {
				String components = source.substring(openParen + 1, closeParen).trim();
				String modifiedSource = addJsonPropertyAnnotations(source, openParen, components);

				// Ensure the Jackson import is present
				if (!source.contains("import com.fasterxml.jackson.annotation.JsonProperty;")) {
					int insertPoint = source.indexOf("package ");
					if (insertPoint != -1) {
						insertPoint = source.indexOf(";", insertPoint) + 1;
						modifiedSource = modifiedSource.substring(0, insertPoint)
								+ "\n\nimport com.fasterxml.jackson.annotation.JsonProperty;"
								+ modifiedSource.substring(insertPoint);
					}
				}

				unit.getBuffer().setContents(modifiedSource);
				unit.save(null, true);
			}
		}
	}

	private String addJsonPropertyAnnotations(String source, int openParen, String components) {

		String[] fields = splitTopLevelCommas(components);

		// Create modified source starting from the beginning up to the fields
		StringBuilder modifiedSource = new StringBuilder(source.substring(0, openParen + 1));

		// Add each field with annotation
		for (int i = 0; i < fields.length; i++) {
			String field = fields[i].trim();
			if (!field.isEmpty() && !field.contains("@JsonProperty")) {
				modifiedSource.append("\n\t\t");
				modifiedSource.append("@JsonProperty(required = true) ");
				modifiedSource.append(field);
				if (i < fields.length - 1) {
					modifiedSource.append(",");
				}
			} else {
				modifiedSource.append("\n\t\t" + field);
				if (i < fields.length - 1) {
					modifiedSource.append(",");
				}
				System.out.println("Skipping field: " + field);
			}
		}

		modifiedSource.append("\n");

		// Add everything after the record declaration
		int endPos = findClosingParenthesis(source, openParen);
		modifiedSource.append(source.substring(endPos));

		return modifiedSource.toString();
	}

	private String[] splitTopLevelCommas(String input) {
		List<String> result = new ArrayList<>();
		int depth = 0;
		int startIndex = 0;

		System.out.println("Splitting: " + input);

		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == '<') {
				depth++;
			} else if (c == '>') {
				depth--;
			} else if (c == ',' && depth == 0) {
				// Only split when we're not inside angle brackets
				result.add(input.substring(startIndex, i).trim());
				startIndex = i + 1;
			}
		}

		// Add the last part
		if (startIndex < input.length()) {
			result.add(input.substring(startIndex).trim());
		}

		return result.toArray(new String[0]);
	}

	private boolean isRecordPresent() {
		if (editor == null) {
			return false;
		}
		try {
			IEditorInput input = editor.getEditorInput();
			if (input instanceof IFileEditorInput fileInput) {
				ICompilationUnit unit = JavaCore.createCompilationUnitFrom(fileInput.getFile());
				if (unit != null) {
					for (IType type : unit.getTypes()) {
						if (type.isRecord()) {
							return true;
						}
					}
				}
			}
		} catch (JavaModelException e) {
			e.printStackTrace();
		}

		return false;
	}

	private int findClosingParenthesis(String source, int startPos) {
		int depth = 0;
		for (int i = startPos; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1; // No matching parenthesis found
	}

	@Override
	public void selectionChanged(IAction arg0, ISelection arg1) {
		// No-op

	}
}
