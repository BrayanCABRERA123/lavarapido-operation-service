package com.lavarapido.operations.infrastructure.adapter.in.web;

import com.lavarapido.operations.domain.model.OperatorProfile;
import com.lavarapido.operations.domain.port.in.OperatorProfileUseCase;
import com.lavarapido.operations.domain.port.in.OperatorProfileUseCase.OperatorProfileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Especialidad y habilidades de los operarios (ficha del operario). Solo ADMIN (/api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin/operators")
class OperatorProfileController {

    private final OperatorProfileUseCase profiles;

    OperatorProfileController(OperatorProfileUseCase profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profiles")
    List<ProfileResponse> profiles() {
        return profiles.profiles().stream().map(ProfileResponse::from).toList();
    }

    @PutMapping("/{id}/profile")
    ProfileResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable int id,
                           @Valid @RequestBody ProfileRequest request) {
        List<OperatorProfile.Skill> skills = request.skills() == null ? List.of()
                : request.skills().stream().map(s -> new OperatorProfile.Skill(s.name(), s.level())).toList();
        return ProfileResponse.from(profiles.updateProfile(id, request.specialty(), skills,
                AuthenticatedUser.userId(jwt)));
    }

    record SkillDto(@NotNull @Size(max = OperatorProfile.MAX_SKILL_NAME) String name,
                    @NotNull OperatorProfile.Level level) {
    }

    record ProfileRequest(@Size(max = OperatorProfile.MAX_SPECIALTY) String specialty,
                          @Size(max = OperatorProfile.MAX_SKILLS) List<@Valid SkillDto> skills) {
    }

    record ProfileResponse(int operatorId, String specialty, List<SkillDto> skills) {

        static ProfileResponse from(OperatorProfileView view) {
            return new ProfileResponse(view.operatorId(), view.profile().specialty(),
                    view.profile().skills().stream().map(s -> new SkillDto(s.name(), s.level())).toList());
        }
    }
}
