package io.floci.oci.services.nosql.query;

import io.floci.oci.services.nosql.query.NosqlTokenizer.Kind;
import io.floci.oci.services.nosql.query.NosqlTokenizer.Token;

import java.util.List;

/** A cursor over a token list with the accept/expect helpers both recursive-descent parsers use. */
public final class NosqlTokens {

    private final List<Token> tokens;
    private int index;

    public NosqlTokens(String statement) {
        this.tokens = NosqlTokenizer.tokenize(statement);
    }

    public Token peek() {
        return tokens.get(index);
    }

    public Token peek(int ahead) {
        return tokens.get(Math.min(index + ahead, tokens.size() - 1));
    }

    public Token next() {
        Token token = tokens.get(index);
        if (token.kind() != Kind.EOF) {
            index++;
        }
        return token;
    }

    public boolean atEnd() {
        return peek().kind() == Kind.EOF;
    }

    public boolean accept(String keyword) {
        if (peek().is(keyword)) {
            index++;
            return true;
        }
        return false;
    }

    public boolean acceptSymbol(String symbol) {
        if (peek().isSymbol(symbol)) {
            index++;
            return true;
        }
        return false;
    }

    public void expect(String keyword) {
        if (!accept(keyword)) {
            throw error("Expected " + keyword);
        }
    }

    public void expectSymbol(String symbol) {
        if (!acceptSymbol(symbol)) {
            throw error("Expected '" + symbol + "'");
        }
    }

    public String identifier(String what) {
        Token token = peek();
        if (token.kind() != Kind.IDENT) {
            throw error("Expected " + what);
        }
        index++;
        return token.text();
    }

    public long integer(String what) {
        Token token = peek();
        if (token.kind() != Kind.NUMBER) {
            throw error("Expected " + what);
        }
        index++;
        try {
            return Long.parseLong(token.text());
        } catch (NumberFormatException e) {
            throw new NosqlSyntaxException("Expected an integer " + what + " at position "
                    + token.position() + ", found " + token.text());
        }
    }

    public void expectEnd() {
        if (!atEnd()) {
            throw error("Unexpected input");
        }
    }

    public NosqlSyntaxException error(String message) {
        Token token = peek();
        String found = token.kind() == Kind.EOF ? "end of statement" : "'" + token.text() + "'";
        return new NosqlSyntaxException(message + " at position " + token.position() + ", found " + found);
    }
}
