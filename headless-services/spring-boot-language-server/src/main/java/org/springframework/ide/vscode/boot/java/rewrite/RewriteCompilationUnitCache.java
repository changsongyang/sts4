/*******************************************************************************
 * Copyright (c) 2022 VMware, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     VMware, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.boot.java.rewrite;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.openrewrite.Parser.Input;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ide.vscode.boot.java.utils.DocumentContentProvider;
import org.springframework.ide.vscode.commons.java.IClasspath;
import org.springframework.ide.vscode.commons.java.IClasspathUtil;
import org.springframework.ide.vscode.commons.java.IJavaProject;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.java.ProjectObserver;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleTextDocumentService;
import org.springframework.ide.vscode.commons.rewrite.java.ORAstUtils;
import org.springframework.ide.vscode.commons.util.text.TextDocument;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;

import reactor.core.Disposable;

public class RewriteCompilationUnitCache implements DocumentContentProvider, Disposable {
	
	private static final Logger logger = LoggerFactory.getLogger(RewriteCompilationUnitCache.class);
	
	private final Object URI_TO_CU_LOCK = new Object(); 

//	private static final long CU_ACCESS_EXPIRATION = 5;
	private JavaProjectFinder projectFinder;
	private ProjectObserver projectObserver;
	
	private final ProjectObserver.Listener projectListener;
	private final SimpleTextDocumentService documentService;

	private final Cache<URI, CompilationUnit> uriToCu;
	private final Cache<IJavaProject, Set<URI>> projectToDocs;
	private final Cache<IJavaProject, JavaParser> javaParsers;

	public RewriteCompilationUnitCache(JavaProjectFinder projectFinder, SimpleLanguageServer server, ProjectObserver projectObserver) {
		this.projectFinder = projectFinder;
		this.projectObserver = projectObserver;
		
		// PT 154618835 - Avoid retaining the CU in the cache as it consumes memory if it hasn't been
		// accessed after some time
		this.uriToCu = CacheBuilder.newBuilder()
//				.expireAfterWrite(CU_ACCESS_EXPIRATION, TimeUnit.MINUTES)
//				.removalListener(new RemovalListener<URI, CompilationUnit>() {
//
//					@Override
//					public void onRemoval(RemovalNotification<URI, CompilationUnit> notification) {
//						invalidateCuForJavaFile(notification.getKey().toString());
//					}
//				})
				.build();
		this.projectToDocs = CacheBuilder.newBuilder().build();
		this.javaParsers = CacheBuilder.newBuilder().build();

		this.documentService = server == null ? null : server.getTextDocumentService();

		// IMPORTANT ===> these notifications arrive within the lsp message loop, so reactions to them have to be fast
		// and not be blocked by waiting for anything
		if (this.documentService != null) {
			this.documentService.onDidChangeContent(doc -> invalidateCuForJavaFile(doc.getDocument().getId().getUri()));
			this.documentService.onDidClose(doc -> invalidateCuForJavaFile(doc.getId().getUri()));
		}

//		if (this.projectFinder != null) {
//			for (IJavaProject project : this.projectFinder.all()) {
//				logger.info("CU Cache: initial lookup env creation for project <{}>", project.getElementName());
//				loadJavaParser(project);
//			}
//		}

		this.projectListener = new ProjectObserver.Listener() {
			
			@Override
			public void deleted(IJavaProject project) {
				logger.debug("CU Cache: deleted project {}", project.getElementName());
				invalidateProject(project);
			}
			
			@Override
			public void created(IJavaProject project) {
				logger.debug("CU Cache: created project {}", project.getElementName());
				invalidateProject(project);
//				loadJavaParser(project);
			}
			
			@Override
			public void changed(IJavaProject project) {
				logger.debug("CU Cache: changed project {}", project.getElementName());
				invalidateProject(project);
				// Load the new cache the value right away
//				loadJavaParser(project);
			}
		};

		if (this.projectObserver != null) {
			this.projectObserver.addListener(this.projectListener);
		}
		
	}

	public void dispose() {
		if (this.projectObserver != null) {
			this.projectObserver.removeListener(this.projectListener);
		}
	}
	
	private JavaParser createJavaParser(IJavaProject project) {
		try {
			List<Path> classpath = getClasspathEntries(project).stream().map(s -> new File(s).toPath()).collect(Collectors.toList());
			JavaParser jp = JavaParser.fromJavaVersion().build();
			jp.setClasspath(classpath);
			return jp;
		} catch (Exception e) {
			logger.error("{}", e);
			return null;
		}
	}


	private JavaParser loadJavaParser(IJavaProject project) {
		try {
			return javaParsers.get(project, () -> createJavaParser(project));
		} catch (ExecutionException e) {
			logger.error("{}", e);
			return null;
		}
	}
	
	private static Set<String> getClasspathEntries(IJavaProject project) throws Exception {
		if (project == null) {
			return Collections.emptySet();
		} else {
			IClasspath classpath = project.getClasspath();
			Stream<File> classpathEntries = IClasspathUtil.getAllBinaryRoots(classpath).stream();
			return classpathEntries
					.filter(file -> file.exists())
					.map(file -> file.getAbsolutePath()).collect(Collectors.toSet());
		}
	}
	
	private void invalidateCuForJavaFile(String uriStr) {
		URI uri = URI.create(uriStr);
		synchronized (URI_TO_CU_LOCK) {
			if (uriToCu.getIfPresent(uri) != null) {
				uriToCu.invalidate(uri);
				Optional<IJavaProject> project = projectFinder.find(new TextDocumentIdentifier(uriStr));
				if (project.isPresent()) {
					
					//TODO There seems to be an issue with java parser #reset() call. After resetting it
					// still complains that it needs to be reset
//					JavaParser parser = javaParsers.getIfPresent(project.get());
//					if (parser != null) {
//						parser.reset();
//					}
					javaParsers.invalidate(project.get());
				}
			}
		}
	}

	private void invalidateProject(IJavaProject project) {
		logger.info("CU Cache: invalidate project <{}>", project.getElementName());

		synchronized (URI_TO_CU_LOCK) {
			Set<URI> docUris = projectToDocs.getIfPresent(project);
			if (docUris != null) {
				uriToCu.invalidateAll(docUris);
				projectToDocs.invalidate(project);
			}
			javaParsers.invalidate(project);
		}
	}

	@Override
	public String fetchContent(URI uri) throws Exception {
		if (documentService != null) {
			TextDocument document = documentService.getLatestSnapshot(uri.toString());
			if (document != null) {
				return document.get();
			}
		}
		return IOUtils.toString(uri);
	}
	
	public CompilationUnit getCU(IJavaProject project, URI uri) {
		try {
			if (project != null) {
				synchronized (URI_TO_CU_LOCK) {
					try {
						return uriToCu.get(uri, () -> {
							boolean newParser = javaParsers.getIfPresent(project) == null;
							try {
								logger.debug("Parsing CU {}", uri);
								JavaParser javaParser = loadJavaParser(project);
								Input input = new Input(Paths.get(uri), () -> {
									try {
										return new ByteArrayInputStream(fetchContent(uri).getBytes());
									} catch (Exception e) {
										throw new IllegalStateException("Unexpected error fetching document content");
									}
								});
								
								List<CompilationUnit> cus = ORAstUtils.parseInputs(javaParser, List.of(input));
														
								CompilationUnit cu = cus.get(0);
									
								if (cu != null) {						
									projectToDocs.get(project, () -> new HashSet<>()).add(uri);
									return cu;
								} else {
									throw new IllegalStateException("Failed to parse Java source");
								}
							} catch (Exception e) {
								if (newParser) {
									javaParsers.invalidate(project);
								}
								throw e;
							}
							
						});
					} catch (UncheckedExecutionException e1) {
						if (e1.getCause() instanceof IllegalStateException) {
							logger.error("", e1);
						} else {
							// ignore errors from rewrite parser. There could be many parser exceptions due to
							// user incrementally typing code's text
						}
						return null;
					} catch (Exception e) {
						logger.error("", e);
						return null;
					}
				}
			}
		} catch (Exception e) {
			logger.error("", e);
		}
		return null;
	}
	
	/**
	 * Does not need to be via callback - kept the same in order to keep the same API to replace JDT with Rewrite in distant future
	 */
	public <T> T withCompilationUnit(IJavaProject project, URI uri, Function<CompilationUnit, T> requestor) {
		logger.info("CU Cache: work item submitted for doc {}", uri.toString());
		CompilationUnit cu = getCU(project, uri);
		if (cu != null) {
			try {
				logger.info("CU Cache: start work on AST for {}", uri.toString());
				return requestor.apply(cu);
			} catch (CancellationException e) {
				throw e;
			} catch (Exception e) {
				logger.error("", e);
			} finally {
				logger.info("CU Cache: end work on AST for {}", uri.toString());
			}
		}
		return requestor.apply(null);
	}
	
	public List<CompilationUnit> getCompiulationUnits(IJavaProject project) {
		List<Path> javaFiles = IClasspathUtil.getProjectJavaSourceFolders(project.getClasspath()).flatMap(folder -> {
			try {
				return Files.walk(folder.toPath());
			} catch (IOException e) {
				logger.error("", e);
			}
			return Stream.empty();
		}).filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".java")).collect(Collectors.toList());
		JavaParser javaParser = loadJavaParser(project);
		return ORAstUtils.parse(javaParser, javaFiles);
	}
	
}
