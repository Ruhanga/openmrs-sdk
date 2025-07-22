package org.openmrs.maven.plugins.utility;


import lombok.Getter;
import lombok.Setter;
import org.apache.maven.it.VerificationException;
import org.junit.Test;
import org.openmrs.maven.plugins.AbstractMavenIT;
import org.openmrs.maven.plugins.model.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import bsh.StringUtil;

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
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import org.apache.commons.io.FileUtils;
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

			OpenmrsModuleResolver resolver = new OpenmrsModuleResolver(getMavenEnvironment());
			resolver.resolve("org.openmrs.module", "coreapps-omod", "2.1.0");
			resolver.printResults();

			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("idgen-omod", "4.0.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), false);
		});
	}

	public class OpenmrsModuleResolver {

        private final Logger log = LoggerFactory.getLogger(getClass());

        private final Map<String, Artifact> resolved = new HashMap<>();

        private final Map<String, Artifact> unResolved = new HashMap<>();

        private final Artifact openmrsWar = new Artifact(null, null, null);

        private final List<String> groupIds = Arrays.asList(Artifact.GROUP_MODULE, "org.openmrs");

        private final List<String> types = Arrays.asList("jar", "omod");

        private final MavenEnvironment mavenEnvironment;

        private final File tempWorkingDir;

        public OpenmrsModuleResolver(MavenEnvironment mavenEnvironment) {
            this.mavenEnvironment = mavenEnvironment;
            this.tempWorkingDir = new File(mavenEnvironment.getMavenProject().getBuild().getDirectory(), "moduleBasedArtifats");
            tempWorkingDir.mkdirs();

        }
        
        public void resolve(String groupId, String artifactId, String version) throws Exception {
            resolveRecursive(groupId, artifactId, version);
            resolved.keySet().forEach(unResolved::remove);
            FileUtils.deleteQuietly(tempWorkingDir);
        }

        private void resolveRecursive(String groupId, String artifactId, String version) throws Exception {
            
            List<Artifact> dependencies = new ArrayList<>();
            String moduleId = null;
            String actualArtifactId = artifactId;
            
            try {
                // Ensure artifactId ends with -omod
                actualArtifactId = artifactId.contains("-omod") ? artifactId : artifactId + "-omod";
                moduleId = actualArtifactId.replaceAll("(-omod|-module)$", "");

                log.info("Resolving: groupId={}, artifactId={}, version={}", groupId, actualArtifactId, version);

                Artifact current = resolved.get(moduleId);
                if (current != null && compareVersions(current.getVersion(), version) >= 0) {
                    log.info("Already resolved {} at same or higher version ({} >= {})", moduleId, current.getVersion(), version);
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
                log.warn("Failed to resolve: groupId={}, artifactId={}, version={}", groupId, actualArtifactId, version);
                log.warn("Reason: {}: {}", e.getClass(), e.getMessage());
                return;
            }
            for (Artifact dep : dependencies) {
                resolveRecursive(dep.getGroupId(), dep.getArtifactId(), dep.getVersion());
            }
        }

        private File downloadJar(String groupId, String artifactId, String version) throws Exception {
            
			ArtifactHelper artifactHelper = new ArtifactHelper(mavenEnvironment);
			boolean downloaded = false;
            for (String gId : groupIds) {
                for (String ext : types) {
                    try {
                        artifactHelper.downloadArtifact(
                            new Artifact(artifactId, version, gId, ext),
                            new File(mavenEnvironment.getMavenProject().getBuild().getDirectory()),
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

            for (String ext : types) {
                File downloadedArtifact = new File(tempWorkingDir, artifactId.replace("-omod", "") + "-" + version + "." + ext);
                if (downloadedArtifact.exists()) {
                    return downloadedArtifact;
                }
            }
            throw new RuntimeException("Could not determine file extension for artifact: " + groupId + ":" + artifactId + ":" + version);
        }

        private List<Artifact> parseDependenciesFromJar(File jarFile) throws Exception {

            System.out.println("zzzzzzzzzzzzz       : " + jarFile.getAbsolutePath());
            List<Artifact> dependencies = new ArrayList<>();
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
					
                    NodeList requiredModules = doc.getElementsByTagName("require_module");

                    for (int i = 0; i < requiredModules.getLength(); i++) {
                        Element el = (Element) requiredModules.item(i);
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
                        dependencies.add(new Artifact(artifactId, version, groupId));
                    }
                }
            }
            return dependencies;
        }

       private int compareVersions(String v1, String v2) {
            String[] qualifiers = {"alpha", "beta", "SNAPSHOT"};

            String base1 = v1.split("-(?=alpha|beta|SNAPSHOT)")[0];
            String base2 = v2.split("-(?=alpha|beta|SNAPSHOT)")[0];

            String q1 = v1.contains("-") ? v1.substring(v1.indexOf("-") + 1) : "";
            String q2 = v2.contains("-") ? v2.substring(v2.indexOf("-") + 1) : "";

            String[] parts1 = base1.split("\\.");
            String[] parts2 = base2.split("\\.");

            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
                int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
                if (p1 != p2) return Integer.compare(p1, p2);
            }

            // If base versions equal, compare qualifiers
            if (!q1.equals(q2)) {
                if (q1.isEmpty()) return 1; // release > pre-release
                if (q2.isEmpty()) return -1;

                int i1 = Arrays.asList(qualifiers).indexOf(q1);
                int i2 = Arrays.asList(qualifiers).indexOf(q2);
                return Integer.compare(i1, i2);
            }

            return 0;
        }

        public void printResults() {
            log.info("");
            log.info("=================================== Resolved Modules ===================================");
            log.info(String.format("%-30s | %-25s | %s", "Module ID", "Group ID", "Version"));
            log.info("----------------------------------------------------------------------------------------");

            resolved.values().forEach(m ->
                log.info(String.format("%-30s | %-25s | %s", m.getArtifactId(), m.getGroupId(), m.getVersion()))
            );
            log.info("");
            log.info("=================================== Unresolved Modules =================================");
            log.info(String.format("%-30s | %-25s | %s", "Module ID", "Group ID", "Version"));
            log.info("----------------------------------------------------------------------------------------");
            unResolved.values().forEach(m ->
                log.info(String.format("%-30s | %-25s | %s", m.getArtifactId(), m.getGroupId(), m.getVersion()))
            );
            log.info("");
        }
    }
}
