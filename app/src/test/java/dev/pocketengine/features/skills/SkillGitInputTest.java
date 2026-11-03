package dev.pocketengine.features.skills;

import org.junit.Test;
import static org.junit.Assert.*;

/** Human links normalize into the same stored source; they do not relax its trust boundary. */
public final class SkillGitInputTest {
    @Test public void commonLinksSelectOnlyTheReviewedSkillDirectory() {
        SkillGitSource expected=new SkillGitSource("https://github.com/demo/skills","main","selected");
        for(String url:new String[]{
                "https://github.com/demo/skills/tree/main/selected",
                "https://github.com/demo/skills/blob/main/selected/SKILL.md",
                "https://github.com/demo/skills/raw/main/selected/SKILL.md",
                "https://raw.githubusercontent.com/demo/skills/main/selected/SKILL.md",
                "https://raw.githubusercontent.com/demo/skills/refs/heads/main/selected/SKILL.md"})
            assertEquals(url,expected,SkillGitSource.fromInput(url,"main",""));
        assertEquals(expected,SkillGitSource.fromInput("https://github.com/demo/skills.git/","main","selected"));
        assertEquals("",SkillGitSource.fromInput("https://github.com/demo/skills/blob/main/SKILL.md","main","").subpath);
    }

    @Test public void slashRefIsExplicitAndConflictingDirectoryNeverFallsBack() {
        String url="https://github.com/demo/skills/tree/release/mobile/v2/selected";
        SkillGitSource selected=SkillGitSource.fromInput(url,"release/mobile/v2","");
        assertEquals("release/mobile/v2",selected.ref);assertEquals("selected",selected.subpath);
        assertThrows(IllegalArgumentException.class,()->SkillGitSource.fromInput(url,"",""));
        assertThrows(IllegalArgumentException.class,()->SkillGitSource.fromInput(url,"main",""));
        assertThrows(IllegalArgumentException.class,()->SkillGitSource.fromInput(url,"release/mobile/v2","other"));
        assertEquals(new SkillGitSource("https://github.com/demo/skills","a".repeat(40),"selected"),
                SkillGitSource.fromInput("https://github.com/demo/skills/blob/"+"a".repeat(40)+"/selected/SKILL.md","a".repeat(40),"selected"));
    }

    @Test public void credentialsEscapesOtherFilesAndUntrustedHostsAreRefused() {
        for(String url:new String[]{
                "http://github.com/demo/skills/tree/main/selected",
                "https://token@github.com/demo/skills/tree/main/selected",
                "https://github.com:443/demo/skills/tree/main/selected",
                "https://github.com/demo/skills/blob/main/selected/SKILL.md?token=secret",
                "https://github.com/demo/skills/blob/main/selected/SKILL.md#top",
                "https://github.com/demo/skills/tree/main/../outside",
                "https://github.com/demo/skills/tree/main/%2e%2e/outside",
                "https://github.com/demo/skills/tree/main/selected%2fother",
                "https://github.com/demo/skills/tree/main//selected",
                "https://github.com/demo/skills/blob/main/selected/README.md",
                "https://github.com/demo/skills/releases/latest",
                "https://raw.githubusercontent.com.attacker.invalid/demo/skills/main/selected/SKILL.md"})
            assertThrows(url,IllegalArgumentException.class,()->SkillGitSource.fromInput(url,"main",""));
    }
}
