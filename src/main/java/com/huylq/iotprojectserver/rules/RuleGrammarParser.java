package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.common.error.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The restricted expression grammar (System Design §5.6, T8 "never {@code eval}") — a
 * hand-rolled tokenizer + recursive-descent parser with no dependency on any
 * general-purpose expression/scripting engine. Every accepted token is enumerated here by
 * hand; there is no reflection, no I/O, and no code-execution primitive anywhere in this
 * class for a malicious/buggy rule to reach.
 *
 * <p>Condition grammar: {@code clause ( ('&&' clause)* | ('||' clause)* )} where {@code
 * clause := IDENT '.' IDENT comparator literal}. Mixing {@code &&}/{@code ||} in one
 * condition is rejected — see {@link RuleCondition}.
 *
 * <p>Action grammar: {@code effect (';' effect)*} where {@code effect} is a {@code
 * command(targetId, action, {key: value, ...})} or {@code alert(type, severity)} call.
 *
 * <p>Both parsers throw {@link ApiException#unprocessable} with the offending token and
 * its position on any syntax error, per API §9 ("422 with the offending token").
 */
public final class RuleGrammarParser {

  private RuleGrammarParser() {
  }

  // ---- condition -------------------------------------------------------------------------

  public static RuleCondition parseCondition(String text) {
    List<Token> tokens = tokenize(text);
    ConditionParser parser = new ConditionParser(tokens, text);
    RuleCondition result = parser.parse();
    parser.expectEnd();
    return result;
  }

  // ---- action ----------------------------------------------------------------------------

  public static RuleAction parseAction(String text) {
    List<Token> tokens = tokenize(text);
    ActionParser parser = new ActionParser(tokens, text);
    RuleAction result = parser.parse();
    parser.expectEnd();
    return result;
  }

  // ---- shared tokenizer --------------------------------------------------------------------

  private enum TokenType {
    IDENT, NUMBER, OP, LOGICAL, DOT, COMMA, LPAREN, RPAREN, LBRACE, RBRACE, COLON, SEMICOLON, EOF
  }

  private record Token(TokenType type, String text, int pos) {
  }

  private static final Pattern TOKEN_PATTERN = Pattern.compile(
      "(?<WS>\\s+)"
          + "|(?<OP>==|!=|>=|<=|>|<)"
          + "|(?<LOGICAL>&&|\\|\\|)"
          + "|(?<NUMBER>-?\\d+(?:\\.\\d+)?)"
          + "|(?<IDENT>[A-Za-z_][A-Za-z0-9_]*)"
          + "|(?<DOT>\\.)"
          + "|(?<COMMA>,)"
          + "|(?<LPAREN>\\()"
          + "|(?<RPAREN>\\))"
          + "|(?<LBRACE>\\{)"
          + "|(?<RBRACE>\\})"
          + "|(?<COLON>:)"
          + "|(?<SEMICOLON>;)");

  private static List<Token> tokenize(String text) {
    if (text == null || text.isBlank()) {
      throw ApiException.unprocessable("Expression must not be blank");
    }
    List<Token> tokens = new ArrayList<>();
    Matcher m = TOKEN_PATTERN.matcher(text);
    int pos = 0;
    while (pos < text.length()) {
      if (!m.find(pos) || m.start() != pos) {
        throw ApiException.unprocessable(
            "Unexpected character '" + text.charAt(pos) + "' at position " + pos);
      }
      if (m.group("WS") == null) {
        TokenType type = tokenTypeOf(m);
        tokens.add(new Token(type, m.group(), pos));
      }
      pos = m.end();
    }
    tokens.add(new Token(TokenType.EOF, "<end>", text.length()));
    return tokens;
  }

  private static TokenType tokenTypeOf(Matcher m) {
    for (TokenType t : TokenType.values()) {
      if (t == TokenType.EOF) continue;
      String group = m.group(t.name());
      if (group != null) return t;
    }
    throw new IllegalStateException("Unreachable: token matched but no named group set");
  }

  // ---- condition parser ---------------------------------------------------------------------

  private static final class ConditionParser {
    private final List<Token> tokens;
    private final String source;
    private int idx = 0;

    ConditionParser(List<Token> tokens, String source) {
      this.tokens = tokens;
      this.source = source;
    }

    RuleCondition parse() {
      List<RuleCondition.Clause> clauses = new ArrayList<>();
      clauses.add(clause());
      RuleCondition.Combinator combinator = RuleCondition.Combinator.SINGLE;
      while (peek().type() == TokenType.LOGICAL) {
        RuleCondition.Combinator next = "&&".equals(peek().text())
            ? RuleCondition.Combinator.AND : RuleCondition.Combinator.OR;
        if (combinator == RuleCondition.Combinator.SINGLE) {
          combinator = next;
        } else if (combinator != next) {
          throw error("Cannot mix && and || in one condition", peek());
        }
        advance();
        clauses.add(clause());
      }
      return new RuleCondition(clauses, combinator);
    }

    private RuleCondition.Clause clause() {
      Token zone = expect(TokenType.IDENT, "zone identifier");
      expect(TokenType.DOT, "'.'");
      Token sensorType = expect(TokenType.IDENT, "sensorType identifier");
      Token opToken = expect(TokenType.OP, "comparator (==, !=, >, <, >=, <=)");
      RuleCondition.Operator op = operatorOf(opToken);
      Token literalToken = advance();
      Object literal = literalOf(literalToken);
      if (literal instanceof Boolean && op != RuleCondition.Operator.EQ && op != RuleCondition.Operator.NE) {
        throw error("Boolean literal only supports == or !=", opToken);
      }
      return new RuleCondition.Clause(zone.text(), sensorType.text(), op, literal);
    }

    private Object literalOf(Token t) {
      if (t.type() == TokenType.IDENT && ("true".equals(t.text()) || "false".equals(t.text()))) {
        return Boolean.parseBoolean(t.text());
      }
      if (t.type() == TokenType.NUMBER) {
        return Double.parseDouble(t.text());
      }
      throw error("Expected a boolean or numeric literal", t);
    }

    private RuleCondition.Operator operatorOf(Token t) {
      return switch (t.text()) {
        case "==" -> RuleCondition.Operator.EQ;
        case "!=" -> RuleCondition.Operator.NE;
        case ">" -> RuleCondition.Operator.GT;
        case "<" -> RuleCondition.Operator.LT;
        case ">=" -> RuleCondition.Operator.GE;
        case "<=" -> RuleCondition.Operator.LE;
        default -> throw error("Unknown comparator", t);
      };
    }

    void expectEnd() {
      if (peek().type() != TokenType.EOF) {
        throw error("Unexpected trailing token", peek());
      }
    }

    private Token peek() {
      return tokens.get(idx);
    }

    private Token advance() {
      return tokens.get(idx++);
    }

    private Token expect(TokenType type, String what) {
      Token t = peek();
      if (t.type() != type) {
        throw error("Expected " + what, t);
      }
      return advance();
    }

    private ApiException error(String message, Token t) {
      return ApiException.unprocessable(
          message + " — found '" + t.text() + "' at position " + t.pos() + " in: " + source);
    }
  }

  // ---- action parser ------------------------------------------------------------------------

  private static final class ActionParser {
    private final List<Token> tokens;
    private final String source;
    private int idx = 0;

    ActionParser(List<Token> tokens, String source) {
      this.tokens = tokens;
      this.source = source;
    }

    RuleAction parse() {
      List<RuleAction.Effect> effects = new ArrayList<>();
      effects.add(effect());
      while (peek().type() == TokenType.SEMICOLON) {
        advance();
        effects.add(effect());
      }
      return new RuleAction(effects);
    }

    private RuleAction.Effect effect() {
      Token name = expect(TokenType.IDENT, "'command' or 'alert'");
      expect(TokenType.LPAREN, "'('");
      RuleAction.Effect effect = switch (name.text()) {
        case "command" -> commandEffect();
        case "alert" -> alertEffect();
        default -> throw error("Unknown effect '" + name.text() + "' — only command/alert are allowed", name);
      };
      expect(TokenType.RPAREN, "')'");
      return effect;
    }

    private RuleAction.CommandEffect commandEffect() {
      Token targetId = expect(TokenType.IDENT, "targetId identifier");
      expect(TokenType.COMMA, "','");
      Token action = expect(TokenType.IDENT, "action identifier");
      expect(TokenType.COMMA, "','");
      Map<String, Object> parameters = object();
      return new RuleAction.CommandEffect(targetId.text(), action.text(), parameters);
    }

    private RuleAction.AlertEffect alertEffect() {
      Token type = expect(TokenType.IDENT, "alert type identifier");
      expect(TokenType.COMMA, "','");
      Token severity = expect(TokenType.IDENT, "severity identifier");
      return new RuleAction.AlertEffect(type.text(), severity.text());
    }

    private Map<String, Object> object() {
      expect(TokenType.LBRACE, "'{'");
      Map<String, Object> params = new LinkedHashMap<>();
      if (peek().type() != TokenType.RBRACE) {
        pair(params);
        while (peek().type() == TokenType.COMMA) {
          advance();
          pair(params);
        }
      }
      expect(TokenType.RBRACE, "'}'");
      return params;
    }

    private void pair(Map<String, Object> params) {
      Token key = expect(TokenType.IDENT, "parameter name");
      expect(TokenType.COLON, "':'");
      Token value = advance();
      Object v = switch (value.type()) {
        case NUMBER -> Double.parseDouble(value.text());
        case IDENT -> value.text();
        default -> throw error("Expected a parameter value (identifier or number)", value);
      };
      if (params.putIfAbsent(key.text(), v) != null) {
        throw error("Duplicate parameter '" + key.text() + "'", key);
      }
    }

    void expectEnd() {
      if (peek().type() != TokenType.EOF) {
        throw error("Unexpected trailing token", peek());
      }
    }

    private Token peek() {
      return tokens.get(idx);
    }

    private Token advance() {
      return tokens.get(idx++);
    }

    private Token expect(TokenType type, String what) {
      Token t = peek();
      if (t.type() != type) {
        throw error("Expected " + what, t);
      }
      return advance();
    }

    private ApiException error(String message, Token t) {
      return ApiException.unprocessable(
          message + " — found '" + t.text() + "' at position " + t.pos() + " in: " + source);
    }
  }
}
