package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.ObjectEntry;
import com.github.andreasarvidsson.eld.parser.ObjectMember;
import com.github.andreasarvidsson.eld.parser.ObjectSpread;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.LambdaExpression;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.Visibility;

public final class SemanticAnalyzerObjects {
    private final SemanticAnalyzer analyzer;
    private final SemanticModel model;

    public SemanticAnalyzerObjects(
        final SemanticAnalyzer analyzer,
        final SemanticModel model
    ) {
        this.analyzer = analyzer;
        this.model = model;
    }

    public void analyzeObjectExpression(
        final ObjectExpression object,
        final SemanticContext context,
        final InterfaceType type
    ) {
        final InterfaceContract contract = model.getInterface(type);
        if (
            type.javaClass() != null && type.javaClass() != Comparable.class
                && type.javaClass() != Comparator.class
        ) {
            throw new SemanticException(
                object.range(),
                "Java collection types require a Java collection instance"
            );
        }
        final List<ObjectEntry> evaluation = new ArrayList<>();
        final Map<String, ObjectMember> effective = new LinkedHashMap<>();
        final Set<String> explicitNames = new HashSet<>();
        for (final ObjectEntry entry : object.members()) {
            if (entry instanceof ObjectSpread spread) {
                evaluation.add(spread);
                final Type source =
                    analyzer.analyzeExpression(spread.value(), context);
                final Map<String, Symbol> supplied =
                    spreadSymbols(source, spread.range());
                for (final var suppliedMember : supplied.entrySet()) {
                    final String name = suppliedMember.getKey();
                    final IdentifierExpression identifier =
                        new IdentifierExpression(name, spread.range());
                    final MemberExpression access =
                        new MemberExpression(
                            spread.value(),
                            identifier,
                            spread.range()
                        );
                    final ObjectMember member =
                        new ObjectMember(
                            new IdentifierDeclaration(name, spread.range()),
                            access,
                            spread.range()
                        );
                    analyzer.analyzeExpressionAsCallee(
                        access,
                        context,
                        suppliedMember.getValue() instanceof FunctionSymbol
                    );
                    if (suppliedMember.getValue() instanceof FunctionSymbol) {
                        model.setSpreadMethod(member);
                    }
                    evaluation.add(member);
                    effective.put(name, member);
                }
            }
            else {
                final ObjectMember member = (ObjectMember) entry;
                final String name = member.name().name();
                if (!explicitNames.add(name)) {
                    throw new SemanticException(
                        member.range(),
                        "Duplicate object member '%s'",
                        name
                    );
                }
                evaluation.add(member);
                effective.put(name, member);
            }
        }
        final Set<String> present = effective.keySet();
        model.setObjectMembers(
            object,
            new ArrayList<>(effective.values()),
            evaluation
        );
        // Analyze overridden values too, without validating them against the final contract.
        for (final ObjectEntry entry : evaluation) {
            if (
                entry instanceof ObjectMember member
                    && !member.equals(effective.get(member.name().name()))
                    && !(member.value() instanceof MemberExpression
                        && model.isSpreadMethod(member))
            ) {
                final Symbol target =
                    contract.fields().containsKey(member.name().name())
                        ? contract.fields().get(member.name().name())
                        : contract.methods().get(member.name().name());
                final Type actual =
                    analyzer.analyzeExpression(
                        member.value(),
                        context,
                        target == null ? null : target.type()
                    );
                if (actual == BuiltinType.VOID) {
                    throw new SemanticException(
                        member.value().range(),
                        "Object member '%s' requires a value",
                        member.name().name()
                    );
                }
            }
        }
        for (final ObjectMember member : effective.values()) {
            final String name = member.name().name();
            final VariableSymbol field = contract.fields().get(name);
            final FunctionSymbol method = contract.methods().get(name);
            if (field == null && method == null) {
                throw new SemanticException(
                    member.range(),
                    "Unknown object member '%s' of interface %s",
                    name,
                    type
                );
            }
            if (field != null && method != null) {
                throw new SemanticException(
                    member.range(),
                    "Object member '%s' cannot implement both a field and a method",
                    name
                );
            }
            final Symbol symbol =
                field != null ? field : Objects.requireNonNull(method);
            if (
                method != null && !model.isSpreadMethod(member)
                    && !(SemanticAnalyzerExpressions
                        .unwrap(member.value()) instanceof LambdaExpression)
            ) {
                throw new SemanticException(
                    member.value().range(),
                    "Interface method '%s' requires a lambda",
                    name
                );
            }
            final Type actual =
                model.isSpreadMethod(member)
                    ? model.getExpressionType(member.value())
                    : analyzer.analyzeExpression(
                        member.value(),
                        context,
                        symbol.type()
                    );
            if (
                analyzer.resolveAssignType(
                    actual,
                    symbol.type(),
                    member.value()
                ) == null
            ) {
                throw new SemanticException(
                    member.value().range(),
                    "Member '%s' expects %s, found %s",
                    name,
                    symbol.type(),
                    actual
                );
            }
        }
        final Set<String> required =
            new java.util.LinkedHashSet<>(contract.fields().keySet());
        required.addAll(contract.methods().keySet());
        for (final String name : required) {
            if (!present.contains(name)) {
                throw new SemanticException(
                    object.range(),
                    "Object literal does not implement %s: missing member '%s'",
                    type,
                    name
                );
            }
        }
    }

    public InterfaceType inferSpreadObject(
        final ObjectExpression object,
        final SemanticContext context
    ) {
        final InterfaceType existing = analyzer.inferredObjects().get(object);
        if (existing != null) {
            return existing;
        }
        final Map<String, VariableSymbol> fields = new LinkedHashMap<>();
        final Map<String, FunctionSymbol> methods = new LinkedHashMap<>();
        for (final ObjectEntry entry : object.members()) {
            if (entry instanceof ObjectSpread spread) {
                final Map<String, Symbol> symbols =
                    spreadSymbols(
                        analyzer.analyzeExpression(spread.value(), context),
                        spread.range()
                    );
                for (final Symbol symbol : symbols.values()) {
                    fields.remove(symbol.name());
                    methods.remove(symbol.name());
                    if (symbol instanceof VariableSymbol field) {
                        fields.put(field.name(), field);
                    }
                    else if (symbol instanceof FunctionSymbol method) {
                        methods.put(method.name(), method);
                    }
                }
            }
            else {
                final ObjectMember member = (ObjectMember) entry;
                final String name = member.name().name();
                final FunctionSymbol method = methods.get(name);
                final Type value =
                    analyzer.analyzeExpression(
                        member.value(),
                        context,
                        method == null ? null : method.type()
                    );
                if (method == null) {
                    fields.put(
                        name,
                        new VariableSymbol(
                            member.name(),
                            value,
                            Mutability.CONST
                        )
                    );
                }
            }
        }
        final InterfaceType type =
            new InterfaceType("$spread" + model.getInterfaceTypes().size());
        model.setInterface(
            type,
            new InterfaceContract(List.of(), fields, methods)
        );
        analyzer.inferredObjects().put(object, type);
        return type;
    }

    private Map<String, Symbol> spreadSymbols(
        final Type source,
        final Range range
    ) {
        final Map<String, Symbol> result = new LinkedHashMap<>();
        if (
            source instanceof InterfaceType contract
                && contract.javaClass() == null
        ) {
            result.putAll(model.getInterface(contract).fields());
            result.putAll(model.getInterface(contract).methods());
        }
        else if (source instanceof ClassType cls) {
            for (ClassType current = cls; current != null; current =
                model.getSuperclass(current)) {
                for (final FunctionSymbol method : analyzer.classMethods()
                    .getOrDefault(current, Map.of())
                    .values()) {
                    if (
                        model.getMemberVisibility(method) == Visibility.PUBLIC
                    ) {
                        result.putIfAbsent(method.name(), method);
                    }
                }
                final Scope scope = analyzer.classScopes().get(current);
                if (scope != null) {
                    for (final Symbol symbol : scope.symbols()) {
                        if (
                            symbol instanceof VariableSymbol
                                && model.getMemberVisibility(
                                    symbol
                                ) == Visibility.PUBLIC
                        ) {
                            result.putIfAbsent(symbol.name(), symbol);
                        }
                    }
                }
            }
        }
        else {
            throw new SemanticException(
                range,
                "Object spread requires a statically known Eld object type, found %s",
                source
            );
        }
        return result;
    }

    @Nullable
    public ClassType classFieldOwner(final ClassType type, final String name) {
        for (ClassType current = type; current != null; current =
            model.getSuperclass(current)) {
            final Scope members = analyzer.classScopes().get(current);
            if (
                members != null
                    && members.resolveLocal(name) instanceof VariableSymbol
            ) {
                return current;
            }
        }
        return null;
    }

    @Nullable
    public ClassType classMethodOwner(final ClassType type, final String name) {
        for (ClassType current = type; current != null; current =
            model.getSuperclass(current)) {
            if (
                analyzer.classMethods()
                    .getOrDefault(current, Map.of())
                    .containsKey(name)
            ) {
                return current;
            }
        }
        return null;
    }

    @Nullable
    public FunctionSymbol classMethod(final ClassType type, final String name) {
        for (ClassType current = type; current != null; current =
            model.getSuperclass(current)) {
            final FunctionSymbol method =
                analyzer.classMethods()
                    .getOrDefault(current, Map.of())
                    .get(name);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    public boolean canAccess(
        final ClassType owner,
        final Visibility visibility
    ) {
        return visibility == Visibility.PUBLIC
            || owner.equals(analyzer.currentAccessClass())
            || (visibility == Visibility.PROTECTED
                && analyzer.currentAccessClass() != null
                && model.isSubclassOf(analyzer.currentAccessClass(), owner));
    }

    @Nullable
    public ClassType memberOwner(final ClassType type, final String name) {
        for (ClassType current = type; current != null; current =
            model.getSuperclass(current)) {
            final Scope scope = analyzer.classScopes().get(current);
            if (scope != null && scope.resolveLocal(name) != null) {
                return current;
            }
        }
        return null;
    }

    public void validateInheritedMember(
        final ClassType type,
        final Symbol symbol
    ) {
        final ClassType base = model.getSuperclass(type);
        if (base == null) {
            return;
        }
        final ClassType methodOwner =
            symbol instanceof FunctionSymbol
                ? classMethodOwner(base, symbol.name())
                : null;
        final ClassType owner =
            methodOwner != null
                ? methodOwner
                : memberOwner(base, symbol.name());
        if (owner == null) {
            return;
        }
        final Symbol inherited =
            methodOwner != null
                ? Objects.requireNonNull(classMethod(base, symbol.name()))
                : Objects.requireNonNull(
                    Objects.requireNonNull(analyzer.classScopes().get(owner))
                        .resolveLocal(symbol.name())
                );
        final Visibility visibility = model.getMemberVisibility(inherited);
        if (visibility == Visibility.PRIVATE) {
            return;
        }
        if (
            symbol instanceof FunctionSymbol
                && inherited instanceof FunctionSymbol
        ) {
            if (!symbol.type().equals(inherited.type())) {
                throw new SemanticException(
                    symbol.range(),
                    "Overriding method '%s' must have the same signature",
                    symbol.name()
                );
            }
            final Visibility declared = model.getMemberVisibility(symbol);
            if (
                declared == Visibility.PRIVATE
                    || (visibility == Visibility.PUBLIC
                        && declared != Visibility.PUBLIC)
            ) {
                throw new SemanticException(
                    symbol.range(),
                    "Overriding method '%s' cannot reduce visibility",
                    symbol.name()
                );
            }
        }
        else if (
            symbol instanceof FunctionSymbol
                || inherited instanceof FunctionSymbol
        ) {
            throw new SemanticException(
                symbol.range(),
                "Inherited member '%s' has a different declaration kind",
                symbol.name()
            );
        }
    }

}
