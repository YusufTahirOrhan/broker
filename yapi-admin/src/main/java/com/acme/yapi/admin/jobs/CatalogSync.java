package com.acme.yapi.admin.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.acme.yapi.admin.catalog.ProjectCatalog;
import com.acme.yapi.admin.project.ProjectRepository;

/**
 * Refreshes display names from the project service. The maintask never changes with the name; a project the
 * service no longer has is marked MISSING, never deleted.
 */
@Component
public class CatalogSync {

    private static final Logger log = LoggerFactory.getLogger(CatalogSync.class);

    private final ProjectRepository projects;
    private final ProjectCatalog catalog;

    public CatalogSync(ProjectRepository projects, ProjectCatalog catalog) {
        this.projects = projects;
        this.catalog = catalog;
    }

    @Scheduled(cron = "${yapi.admin.catalog.sync-cron:0 7 * * * *}")
    public void run() {
        try {
            for (ProjectRepository.Row project : projects.all()) {
                if (project.externalProjectId() == null) {
                    continue;
                }
                catalog.find(project.externalProjectId()).ifPresentOrElse(
                        found -> projects.catalog(project.id(), project.externalProjectId(), found.name(), "LINKED"),
                        () -> {
                            if (!"MISSING".equals(project.catalogStatus())) {
                                log.warn("Koordinatör'de artık yok: {} ({})", project.maintask(),
                                        project.externalProjectId());
                            }
                            projects.catalog(project.id(), project.externalProjectId(), project.name(), "MISSING");
                        });
            }
        } catch (RuntimeException e) {
            log.warn("Katalog senkronizasyonu yapılamadı: {}", e.getMessage());
        }
    }
}
