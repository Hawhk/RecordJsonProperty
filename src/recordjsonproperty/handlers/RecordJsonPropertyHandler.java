package recordjsonproperty.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
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
			action.setEnabled(shouldBeActive());
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
				IType[] types = unit.getTypes();

				for (IType type : types) {
					if (type.isRecord()) {
						addAnnotationsToRecord(unit, type);
					} else if (type.isClass()) {
						addAnnotationsToClass(unit, type);
					}
				}
			}
		} catch (Exception e) {
			MessageDialog.openError(editor.getSite().getShell(), "Error",
					"Error adding annotations: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void addAnnotationsToRecord(ICompilationUnit unit, IType type) throws JavaModelException {
		String start = getStart(type.getSource(), type.getElementName());
		addAnnotations(unit, start);
	}

	private void addAnnotationsToClass(ICompilationUnit unit, IType type) throws JavaModelException {
		IMethod jsonCreatorMethod = null;
		IMethod[] methods = type.getMethods();

		for (IMethod method : methods) {
			jsonCreatorMethod = getIfJsonCreator(method);
			if (jsonCreatorMethod != null) {
				String start = getStart(jsonCreatorMethod.getSource(), jsonCreatorMethod.getElementName());
				addAnnotations(unit, start);
				return;
			}
		}

		if (methods.length == 1) {
			jsonCreatorMethod = methods[0];
			String source = addJsonCreatorAnnotation(unit, jsonCreatorMethod);
			String start = getStart(source, jsonCreatorMethod.getElementName());
			addAnnotations(unit, start);
		} else if (methods.length > 1) {
			MessageDialog.openError(editor.getSite().getShell(), "Error",
					"Multiple methods found. Please add @JsonCreator annotation manually and try again.");
		}

	}

	private String addJsonCreatorAnnotation(ICompilationUnit unit, IMethod jsonCreatorMethod)
			throws JavaModelException {
		String source = unit.getSource();
		StringBuilder modifiedSource = new StringBuilder(
				source.substring(0, source.indexOf(jsonCreatorMethod.getSource())));
		modifiedSource.append("@JsonCreator\n\t");
		modifiedSource.append(jsonCreatorMethod.getSource());
		modifiedSource.append("\n\t");

		// add the rest of class body
		int endPos = source.indexOf("}", source.indexOf(jsonCreatorMethod.getSource()));
		modifiedSource.append(source.substring(endPos + 1));
		source = modifiedSource.toString();
		unit.getBuffer().setContents(source);
		return source;
	}

	private String getStart(String source, String elementName) {
		int methodIndex = source.indexOf(elementName);
		String start = source.substring(methodIndex);

		int depth = 0;
		boolean inAnnotation = false;

		for (int i = 0; i < source.length(); i++) {
			char c = source.charAt(i);

			if (c == '@') {
				inAnnotation = true;
			} else if (inAnnotation && c == '(') {
				depth++;

			} else if (inAnnotation && c == ')') {
				depth--;
			} else if (inAnnotation && depth == 0 && Character.isWhitespace(c)) {
				inAnnotation = false;
			} else if (!inAnnotation && !Character.isWhitespace(c)) {
				// Found start of actual field declaration
				return source.substring(i).substring(methodIndex);
			}

			if (inAnnotation && depth == 0 && c == ')') {
				inAnnotation = false;
			}
		}

		return start;
	}

	private void addAnnotations(ICompilationUnit unit, String start) throws JavaModelException {

		String source = unit.getSource();
		int startPos = source.indexOf(start);
		int openParen = source.indexOf('(', startPos);
		if (openParen != -1) {
			int closeParen = findClosingParenthesis(source, openParen);
			if (closeParen != -1) {
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

	private IMethod getIfJsonCreator(IMethod method) throws JavaModelException {
		IMethod foundMethod = null;
		for (IAnnotation annotation : method.getAnnotations()) {
			if (annotation.getElementName().equals("JsonCreator")) {
				foundMethod = method;
				break;
			}
		}
		return foundMethod;
	}

	private String addJsonPropertyAnnotations(String source, int openParen, String components) {

		// get whitespace before the first non-whitespace character

		String[] fields = splitTopLevelCommas(components);

		// Create modified source starting from the beginning up to the fields
		StringBuilder modifiedSource = new StringBuilder(source.substring(0, openParen + 1));

		// Add each field with annotation
		for (int i = 0; i < fields.length; i++) {
			String field = fields[i];
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

		String beginingParams = "<{[(";
		String endingParams = ">}])";

		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (beginingParams.indexOf(c) != -1) {
				depth++;
			} else if (endingParams.indexOf(c) != -1) {
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

	private boolean shouldBeActive() {
		if (editor == null) {
			return false;
		}
		try {
			IEditorInput input = editor.getEditorInput();
			if (input instanceof IFileEditorInput fileInput) {
				ICompilationUnit unit = JavaCore.createCompilationUnitFrom(fileInput.getFile());
				if (unit != null) {
					for (IType type : unit.getTypes()) {
						if (type.isRecord() || type.isClass()) {
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
