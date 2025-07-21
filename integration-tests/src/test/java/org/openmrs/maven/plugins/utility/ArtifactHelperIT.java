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
import java.io.StringWriter;
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
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

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

        // public class ModuleInfo {
        //     String moduleId;
        //     String groupId;
        //     String version;

        //     ModuleInfo(String moduleId, String groupId, String version) {
        //         this.moduleId = moduleId;
        //         this.groupId = groupId;
        //         this.version = version;
        //     }

        //     @Override
        //     public String toString() {
        //         return moduleId + " | " + groupId + " | " + version;
        //     }
        // }

        private final Map<String, Artifact> resolved = new HashMap<>();

        private final Map<String, Artifact> unResolved = new HashMap<>();

        private final List<String> groupIds = Arrays.asList(Artifact.GROUP_MODULE, "org.openmrs");

        private final List<String> extensions = Arrays.asList("jar", "omod");

        public void resolve(String groupId, String artifactId, String version) throws Exception {
            resolveRecursive(groupId, artifactId, version);
            resolved.keySet().forEach(unResolved::remove);
            
        }

        private void resolveRecursive(String groupId, String artifactId, String version) throws Exception {
            
            List<Dependency> dependencies = new ArrayList<>();
            String moduleId = null;
            String actualArtifactId = artifactId;
            
            try {
                // Ensure artifactId ends with -omod
                actualArtifactId = artifactId.contains("-omod") ? artifactId : artifactId + "-omod";
                moduleId = actualArtifactId.replaceAll("(-omod|-module)$", "");

                System.out.printf("Resolving: groupId=%s, artifactId=%s, version=%s%n", groupId, actualArtifactId, version);

                Artifact current = resolved.get(moduleId);
                if (current != null && compareVersions(current.getVersion(), version) >= 0) {
                    System.out.printf("Already resolved %s at same or higher version (%s >= %s)%n", moduleId, current.getVersion(), version);
                    return;
                } else if (current == null) {
                    // ensure it is valid version by it being greater than 0
                    compareVersions("0", version);
                }

                File jarFile = downloadJar(groupId, actualArtifactId, version);

                // Only add the artefact if it can be downloaded
                resolved.put(moduleId, new Artifact(moduleId, version, groupId));

                dependencies = parseDependenciesFromJar(jarFile);
            } catch(Exception e) {
                unResolved.put(moduleId, new Artifact(moduleId, version, groupId));
                System.err.printf("Failed to resolve: groupId=%s, artifactId=%s, version=%s%n", groupId, actualArtifactId, version);
                System.err.println("Reason: " + e.getMessage());
                e.printStackTrace(System.err);
                return;
            }
            for (Dependency dep : dependencies) {
                resolveRecursive(dep.groupId, dep.artifactId, dep.version);
            }
        }

        private File downloadJar(String groupId, String artifactId, String version) throws Exception {

			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			boolean downloaded = false;
            for (String gId : groupIds) {
                for (String ext : extensions) {
                    try {
                        artifactHelper.downloadArtifact(
                            new Artifact(artifactId, version, gId, ext),
                            getMavenTestDirectory(),
                            false
                        );
                        downloaded = true;
                        break; // success, stop trying
                    } catch (Exception ignored) {
                        // keep trying next combination
                    }
                }
                if (downloaded) break;
            }

            if (!downloaded) {
                throw new RuntimeException("Failed to download artifact: " + groupId + ":" + artifactId + ":" + version);
            }
			File downloadedArtifact = new File(getMavenTestDirectory(), artifactId.replace("-omod", "") + "-" + version + ".jar");
            return downloadedArtifact;
        }

        private List<Dependency> parseDependenciesFromJar(File jarFile) throws Exception {

            System.out.println("zzzzzzzzzzzzz       : " + jarFile.getAbsolutePath());
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

                    // for debugging purposes
                    // {
                    //     Transformer transformer = TransformerFactory.newInstance().newTransformer();
                    //     transformer.setOutputProperty(OutputKeys.INDENT, "no");

                    //     DOMSource source = new DOMSource(doc.getDocumentElement());
                    //     StringWriter writer = new StringWriter();
                    //     StreamResult result = new StreamResult(writer);

                    //     transformer.transform(source, result);

                    //     System.out.println(writer.toString());
                    // }
					
                    NodeList requireModules = doc.getElementsByTagName("require_module");

                    for (int i = 0; i < requireModules.getLength(); i++) {
                        Element el = (Element) requireModules.item(i);
                        String uid = el.getTextContent().trim();
                        String version = el.getAttribute("version").trim();

                        if (!uid.contains(".")) continue;

                        String artifactId = "";
                        String groupId = "";
                        if (uid.startsWith("org.openmrs.module.")) {
                            artifactId = uid.substring("org.openmrs.module.".length());
                            groupId = "org.openmrs.module";
                        } else if (uid.startsWith("org.openmrs.")) {
                            artifactId = uid.substring("org.openmrs.".length());
                            groupId = "org.openmrs";
                        } else {
                            throw new IllegalArgumentException("Unsupported UID format: " + uid);
                        }

                        dependencies.add(new Dependency(groupId, artifactId, version));
                    }
                }
            }
            return dependencies;
        }

        private int compareVersions(String v1, String v2) {
            // Strip off "-SNAPSHOT" and remember if it was a snapshot
            boolean isSnapshot1 = v1.endsWith("-SNAPSHOT");
            boolean isSnapshot2 = v2.endsWith("-SNAPSHOT");

            String base1 = isSnapshot1 ? v1.substring(0, v1.length() - 9) : v1;
            String base2 = isSnapshot2 ? v2.substring(0, v2.length() - 9) : v2;

            String[] a = base1.split("\\.");
            String[] b = base2.split("\\.");

            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int ai = i < a.length ? Integer.parseInt(a[i]) : 0;
                int bi = i < b.length ? Integer.parseInt(b[i]) : 0;
                if (ai != bi) return Integer.compare(ai, bi);
            }

            // If base versions are equal, consider SNAPSHOT < release
            if (isSnapshot1 != isSnapshot2) {
                return isSnapshot1 ? -1 : 1;
            }

            return 0;
        }

        public void printResults() {
            System.out.println("\n===================== Resolved Modules =====================");
            System.out.printf("%-30s | %-40s | %s%n", "Module ID", "Group ID", "Version");
            System.out.println("------------------------------------------------------------" +
                            "--------------------------------------------");
            resolved.values().forEach(m ->
                System.out.printf("%-30s | %-40s | %s%n", m.getArtifactId(), m.getGroupId(), m.getVersion())
            );

            System.out.println("\n===================== Unresolved Modules ===================");
            System.out.printf("%-30s | %-40s | %s%n", "Module ID", "Group ID", "Version");
            System.out.println("------------------------------------------------------------" +
                            "--------------------------------------------");
            unResolved.values().forEach(m ->
                System.out.printf("%-30s | %-40s | %s%n", m.getArtifactId(), m.getGroupId(), m.getVersion())
            );
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
