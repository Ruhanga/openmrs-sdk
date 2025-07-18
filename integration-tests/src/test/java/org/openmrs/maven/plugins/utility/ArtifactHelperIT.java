package org.openmrs.maven.plugins.utility;


import lombok.Getter;
import lombok.Setter;
import org.apache.maven.it.VerificationException;
import org.junit.Test;
import org.openmrs.maven.plugins.AbstractMavenIT;
import org.openmrs.maven.plugins.model.Artifact;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Getter @Setter
public class ArtifactHelperIT extends AbstractMavenIT {

	@Test
	public void downloadArtifact_shouldDownloadToSpecifiedDirectory() throws Exception {
		executeTest(() -> {
			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("idgen-omod", "4.14.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), false);
			File downloadedArtifact = new File(getMavenTestDirectory(), "idgen-4.14.0.jar");
			assertTrue(downloadedArtifact.exists());
		});
	}

	@Test
	public void downloadArtifact_shouldUnpackToSpecifiedDirectory() throws Exception {
		executeTest(() -> {
			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("idgen-omod", "4.14.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), true);
			File downloadedArtifact = new File(getMavenTestDirectory(), "idgen-4.14.0.jar");
			assertFalse(downloadedArtifact.exists());
			File liquibaseXml = new File(getMavenTestDirectory(), "liquibase.xml");
			assertTrue(liquibaseXml.exists());
			File configXml = new File(getMavenTestDirectory(), "config.xml");
			assertTrue(configXml.exists());
		});
	}

	@Test
	public void downloadArtifact_shouldFailIfNoArtifactIsFound() throws Exception {
		
		executeTest(() -> {

			OpenmrsModuleResolver resolver = new OpenmrsModuleResolver();
			resolver.resolve("org.openmrs.module", "coreapps-omod", "2.1.0");
			resolver.printResults();

			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("idgen-omod", "4.0.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), false);
		});
	}

	public class OpenmrsModuleResolver {

        public class ModuleInfo {
            String moduleId;
            String groupId;
            String version;

            ModuleInfo(String moduleId, String groupId, String version) {
                this.moduleId = moduleId;
                this.groupId = groupId;
                this.version = version;
            }

            @Override
            public String toString() {
                return moduleId + " | " + groupId + " | " + version;
            }
        }

        private final Map<String, ModuleInfo> resolved = new HashMap<>();

        public void resolve(String groupId, String artifactId, String version) throws Exception {
            resolveRecursive(groupId, artifactId, version);
        }

        private void resolveRecursive(String groupId, String artifactId, String version) throws Exception {
            artifactId = artifactId.contains("-omod") ? artifactId : artifactId + "-omod";
			
			String moduleId = artifactId.replaceAll("(-omod|-module)$", "");
            ModuleInfo current = resolved.get(moduleId);

            if (current != null && compareVersions(current.version, version) >= 0) return;

            resolved.put(moduleId, new ModuleInfo(moduleId, groupId, version));

            File jarFile = downloadJar(groupId, artifactId, version);
            List<Dependency> dependencies = parseDependenciesFromJar(jarFile);

            for (Dependency dep : dependencies) {
                resolveRecursive(dep.groupId, dep.artifactId, dep.version);
            }
        }

        private File downloadJar(String groupId, String artifactId, String version) throws Exception {

			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact(artifactId, version, groupId, "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), false);
			File downloadedArtifact = new File(getMavenTestDirectory(), artifactId.replace("-omod", "") + "-" + version + ".jar");
            return downloadedArtifact;
        }

        private List<Dependency> parseDependenciesFromJar(File jarFile) throws Exception {
            List<Dependency> dependencies = new ArrayList<>();
            try (JarFile jar = new JarFile(jarFile)) {
                ZipEntry entry = jar.getEntry("config.xml");
                if (entry == null) return dependencies;

                try (InputStream in = jar.getInputStream(entry)) {
					DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

					// Prevent external entities and DTDs
					dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
					dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
					dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
					dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false); // Optional: set true if you want to block all DTDs
					dbf.setNamespaceAware(true);
					dbf.setValidating(false);

					DocumentBuilder builder = dbf.newDocumentBuilder();
					builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

                    Document doc = builder.parse(in);
					
                    NodeList requireModules = doc.getElementsByTagName("require_module");

                    for (int i = 0; i < requireModules.getLength(); i++) {
                        Element el = (Element) requireModules.item(i);
                        String uid = el.getTextContent().trim();
                        String version = el.getAttribute("version").trim();

                        if (!uid.contains(".")) continue;

                        String[] parts = uid.split("\\.");
                        String artifactId = parts[parts.length - 1];
                        String groupId = String.join(".", Arrays.asList(parts).subList(0, parts.length - 1));

                        dependencies.add(new Dependency(groupId, artifactId, version));
                    }
                }
            }
            return dependencies;
        }

        private int compareVersions(String a, String b) {
            String[] x = a.split("\\.");
            String[] y = b.split("\\.");
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                int ai = i < x.length ? Integer.parseInt(x[i]) : 0;
                int bi = i < y.length ? Integer.parseInt(y[i]) : 0;
                if (ai != bi) return Integer.compare(ai, bi);
            }
            return 0;
        }

        public void printResults() {
            resolved.values().forEach(m -> System.out.printf("%-30s | %-40s | %s%n", m.moduleId, m.groupId, m.version));
        }

        private class Dependency {
            String groupId;
            String artifactId;
            String version;

            Dependency(String groupId, String artifactId, String version) {
                this.groupId = groupId;
                this.artifactId = artifactId;
                this.version = version;
            }
        }
    }
}
