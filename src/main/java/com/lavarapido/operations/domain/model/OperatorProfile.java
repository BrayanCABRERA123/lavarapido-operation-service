package com.lavarapido.operations.domain.model;

import com.lavarapido.operations.domain.exception.InvalidValueException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Especialidad y habilidades del operario (ficha del operario). La especialidad es opcional; las
 * habilidades son a lo sumo {@link #MAX_SKILLS}, con nombre único (sin distinguir mayúsculas).
 */
public record OperatorProfile(String specialty, List<Skill> skills) {

    public static final int MAX_SPECIALTY = 80;
    public static final int MAX_SKILLS = 10;
    public static final int MAX_SKILL_NAME = 60;

    public static final OperatorProfile EMPTY = new OperatorProfile(null, List.of());

    public enum Level { CERTIFIED, EXPERT, ADVANCED }

    public record Skill(String name, Level level) {
    }

    public OperatorProfile {
        specialty = specialty == null || specialty.isBlank() ? null : specialty.strip();
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /** Limpia y valida lo que manda el admin; lanza INVALID_OPERATOR_PROFILE si algo no cumple. */
    public static OperatorProfile of(String specialty, List<Skill> skills) {
        OperatorProfile profile = new OperatorProfile(specialty,
                skills == null ? List.of() : skills.stream()
                        .map(s -> new Skill(s.name() == null ? "" : s.name().strip(), s.level()))
                        .toList());
        if (profile.specialty() != null && profile.specialty().length() > MAX_SPECIALTY) {
            throw invalid("The specialty must have at most " + MAX_SPECIALTY + " characters");
        }
        if (profile.skills().size() > MAX_SKILLS) {
            throw invalid("An operator can have at most " + MAX_SKILLS + " skills");
        }
        Set<String> names = new HashSet<>();
        for (Skill skill : profile.skills()) {
            if (skill.name().isEmpty() || skill.name().length() > MAX_SKILL_NAME) {
                throw invalid("Each skill needs a name of at most " + MAX_SKILL_NAME + " characters");
            }
            if (skill.level() == null) {
                throw invalid("Each skill needs a level");
            }
            if (!names.add(skill.name().toLowerCase(Locale.ROOT))) {
                throw invalid("Skill names must be unique");
            }
        }
        return profile;
    }

    private static InvalidValueException invalid(String message) {
        return new InvalidValueException("INVALID_OPERATOR_PROFILE", message);
    }
}
