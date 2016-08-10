/*
 * Copyright (c) 2016 simplity.org
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.simplity.kernel.util;

import java.lang.reflect.Array;
import java.util.Date;
import java.util.regex.Pattern;

import org.simplity.kernel.expr.Expression;
import org.simplity.kernel.expr.InvalidExpressionException;

/**
 * @author rg bandi
 *
 */
public class TextUtil {
	private static final String[] TRUE_VALUES = { "1", "TRUE", "YES" };
	private static final String ARRAY_DELIMITER = ",";
	private static final char DOLLAR = '$';
	private static final char LOWER_A = 'a';
	private static final char LOWER_Z = 'z';
	private static final char A = 'A';
	private static final char Z = 'Z';
	private static final char UNDERSCORE = '_';
	private static final String UNDER_STR = "_";
	private static final int TO_LOWER = LOWER_A - A;
	private static final char DELIMITER = '.';

	/**
	 * convert a name that follows variableName notation to CONSTANT_NAME
	 * notation
	 *
	 * @param variable
	 * @return converted name
	 */
	public static String valueToConstant(String variable) {
		StringBuilder result = new StringBuilder();
		char[] chars = variable.toCharArray();
		for (char ch : chars) {
			if (ch <= LOWER_Z && ch >= LOWER_A) {
				ch = (char) (ch - TO_LOWER);
			} else {
				result.append(UNDERSCORE);
			}
			result.append(ch);
		}
		return result.toString();
	}

	/**
	 * convert a name that follows CONSTANT_NAME notation to variableName
	 * notation
	 *
	 * @param constant
	 * @return converted name
	 */
	public static String constantToValue(String constant) {
		/*
		 * 90 % of our enums are single words
		 */
		String result = constant.toLowerCase();
		if (constant.indexOf(UNDERSCORE) == -1) {
			return result;
		}
		String[] parts = constant.split("_");
		/*
		 * We do not have any enum with more than two words as of now, hence we
		 * do not use string builder
		 */
		result = parts[0];
		for (int i = 1; i < parts.length; i++) {
			String part = parts[i];
			result += (char) (part.charAt(0) - TO_LOWER) + part.substring(1);
		}
		return result;
	}

	/**
	 * simple utility to get the last part of the name
	 *
	 * @param qualifiedName
	 * @return simple name
	 */
	public static String getSimpleName(String qualifiedName) {
		int n = qualifiedName.lastIndexOf(DELIMITER);
		if (n == -1) {
			return qualifiedName;
		}
		return qualifiedName.substring(n + 1);
	}

	/**
	 * parse into a primitive object
	 *
	 * @param text
	 * @param type
	 * @return parse object, or null if it could not be parsed
	 * @throws XmlParseException
	 *             if any issue with parsing the text into appropriate type
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Object parse(String text, Class type) throws XmlParseException {
		String value = text.trim();
		if (type.equals(String.class)) {
			return value;
		}

		if (type.isPrimitive()) {
			if (type.equals(int.class)) {
				return new Integer(value);
			}

			if (type.equals(long.class)) {
				return new Long(value);
			}

			if (type.equals(short.class)) {
				return new Short(value);
			}

			if (type.equals(byte.class)) {
				return new Byte(value);
			}

			if (type.equals(char.class)) {
				if (value.length() == 0) {
					return new Integer(' ');
				}
				return new Integer(value.toCharArray()[0]);
			}

			if (type.equals(boolean.class)) {
				return Boolean.valueOf(parseBoolean(value));
			}

			if (type.equals(float.class)) {
				return new Float(value);
			}

			if (type.equals(double.class)) {
				return new Double(value);
			}
		} else if (type.isEnum()) {
			return Enum.valueOf(type, TextUtil.valueToConstant(value));
		} else if (type.isArray()) {
			Class<?> eleType = type.getComponentType();
			if (ReflectUtil.isValueType(eleType)) {
				return parseArray(eleType, value);
			}
		} else if (type.equals(Expression.class)) {
			try {
				return new Expression(value);
			} catch (InvalidExpressionException e) {
				throw new XmlParseException(e.getMessage());
			}
		} else if (type.equals(Date.class)) {
			Date date = DateUtil.parseYmd(value);
			if (date == null) {
				throw new XmlParseException(value + " is not in yyyy-mm-dd format");
			}
			return date;
		} else if (type.equals(Pattern.class)) {
			return Pattern.compile(value);
		}

		return null;

	}

	/**
	 * parse a comma separated string into an array
	 *
	 * @param type
	 *            of the elements of the array
	 * @param value
	 *            to be parsed
	 * @return array that is parsed from text value
	 * @throws XmlParseException
	 *             for any issue while parsing teh text into an array of this
	 *             type
	 */
	public static Object parseArray(Class<?> type, String value) throws XmlParseException {
		String[] parts = value.split(ARRAY_DELIMITER);
		int nbr = parts.length;
		Object array = Array.newInstance(type, nbr);
		for (int i = 0; i < parts.length; i++) {
			Object thisObject = parse(parts[i].trim(), type);
			Array.set(array, i, thisObject);
		}
		return array;
	}

	/**
	 * parse a text into boolean
	 *
	 * @param value
	 * @return
	 */
	private static boolean parseBoolean(String value) {
		String val = value.toUpperCase();
		for (String trueVal : TRUE_VALUES) {
			if (trueVal.equals(val)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * get the field name for class name
	 *
	 * @param className
	 * @return field name for the class name
	 */
	public static String classNameToName(String className) {
		String result = className;
		char c = result.charAt(0);
		if (c >= A && c <= Z) {
			c = (char) (c + TO_LOWER);
			result = c + result.substring(1);
		}
		return result;
	}

	/**
	 * @param name
	 * @return class name
	 */
	public static String nameToClassName(String name) {
		String result = name;
		char c = result.charAt(0);
		if (c >= LOWER_A && c <= LOWER_Z) {
			c = (char) (c - TO_LOWER);
			result = c + result.substring(1);
		}
		return result;
	}

	/**
	 * camelCase is converted into constant like myName to MY_NAME
	 *
	 * @param name
	 * @return name suitable as constant
	 */
	public static String toUnderscore(String name) {
		StringBuilder sbf = new StringBuilder();
		for (char c : name.toCharArray()) {
			if (c >= A && c <= Z) {
				c = (char) (c + TO_LOWER);
				sbf.append(UNDERSCORE);
			}
			sbf.append(c);
		}
		return sbf.toString();
	}

	/**
	 * convert constants to camel case. like MY_NAME to myName
	 *
	 * @param name
	 * @return camelCased name
	 */
	public static String undoUnderscore(String name) {
		String[] parts = name.split(UNDER_STR);
		if (parts.length == 0) {
			return name;
		}
		StringBuilder result = new StringBuilder(parts[0]);
		for (int i = 1; i < parts.length; i++) {
			result.append(nameToClassName(parts[i]));
		}
		return result.toString();
	}

	/**
	 * check for name of type $fieldName. if this is true, return fieldName,
	 * else return null.
	 *
	 * @param name
	 * @return field name, if name follows convention for field name inside
	 *         constants
	 */
	public static String getFieldName(String name) {
		/*
		 * even "$" is null
		 */
		if (name == null || name.length() <= 1) {
			return null;
		}
		if (name.charAt(0) == DOLLAR) {
			char c = name.charAt(1);
			if ((c >= LOWER_A && c <= LOWER_Z) || (c >= A && c <= Z) || c == UNDERSCORE) {
				return name.substring(1);
			}
		}
		return null;
	}
}