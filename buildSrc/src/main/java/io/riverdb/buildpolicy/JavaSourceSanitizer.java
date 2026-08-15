package io.riverdb.buildpolicy;

/** Masks comments and literals while retaining source offsets and line breaks. */
final class JavaSourceSanitizer {
  private final String source;
  private final StringBuilder result;
  private LexicalState state = LexicalState.CODE;
  private int index;

  private JavaSourceSanitizer(String javaSource) {
    source = javaSource;
    result = new StringBuilder(javaSource.length());
  }

  static String strip(String source) {
    return new JavaSourceSanitizer(source).strip();
  }

  private String strip() {
    while (index < source.length()) {
      switch (state) {
        case CODE -> scanCode();
        case LINE_COMMENT -> scanLineComment();
        case BLOCK_COMMENT -> scanBlockComment();
        case TEXT_BLOCK -> scanTextBlock();
        case STRING, CHARACTER -> scanQuotedLiteral();
      }
      index++;
    }
    return result.toString();
  }

  private void scanCode() {
    char current = current();
    if (current == '/' && next() == '/') {
      mask(2);
      state = LexicalState.LINE_COMMENT;
    } else if (current == '/' && next() == '*') {
      mask(2);
      state = LexicalState.BLOCK_COMMENT;
    } else if (current == '"' && next() == '"' && third() == '"') {
      mask(3);
      state = LexicalState.TEXT_BLOCK;
    } else if (current == '"') {
      result.append(' ');
      state = LexicalState.STRING;
    } else if (current == '\'') {
      result.append(' ');
      state = LexicalState.CHARACTER;
    } else {
      result.append(current);
    }
  }

  private void scanLineComment() {
    char current = current();
    result.append(current == '\n' ? '\n' : ' ');
    if (current == '\n') {
      state = LexicalState.CODE;
    }
  }

  private void scanBlockComment() {
    char current = current();
    if (current == '*' && next() == '/') {
      mask(2);
      state = LexicalState.CODE;
    } else {
      result.append(current == '\n' ? '\n' : ' ');
    }
  }

  private void scanTextBlock() {
    char current = current();
    if (current == '"' && next() == '"' && third() == '"') {
      mask(3);
      state = LexicalState.CODE;
    } else {
      result.append(current == '\n' ? '\n' : ' ');
    }
  }

  private void scanQuotedLiteral() {
    char current = current();
    if (current == '\\' && next() != '\0') {
      mask(2);
      return;
    }
    result.append(current == '\n' ? '\n' : ' ');
    if (state == LexicalState.STRING && current == '"'
        || state == LexicalState.CHARACTER && current == '\'') {
      state = LexicalState.CODE;
    }
  }

  private void mask(int count) {
    for (int offset = 0; offset < count; offset++) {
      result.append(' ');
    }
    index += count - 1;
  }

  private char current() {
    return source.charAt(index);
  }

  private char next() {
    return index + 1 < source.length() ? source.charAt(index + 1) : '\0';
  }

  private char third() {
    return index + 2 < source.length() ? source.charAt(index + 2) : '\0';
  }

  private enum LexicalState {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    CHARACTER,
    TEXT_BLOCK
  }
}
