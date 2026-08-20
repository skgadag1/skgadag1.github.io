package org.lseg.refreshModel;

import com.solidatus.client.model.Comparator;
import com.solidatus.client.model.ModelData;
import com.solidatus.client.model.ModelEntityDataFlat;
import com.solidatus.client.model.ModelQueryData;
import com.solidatus.client.model.ModelTransitionData;
import com.solidatus.client.model.SimpleModelData;
import com.solidatus.spring.SolidatusModel;
import com.solidatus.spring.SolidatusModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class DisplayModelBuilder {

    private static final Logger log = LoggerFactory.getLogger(DisplayModelBuilder.class);

    
    private static final String SOLUID = "Display Model Property";

    
    private static final String ATOMIC_ORIGIN_ID = "Atomic Origin ID";

    
    private static final String SOURCE_MODEL_ID   = "Source Model ID";

    
    private static final String SOURCE_MODEL_NAME = "Source Model Name";

    
    private static final String SOURCE_MODEL_LINK = "Source Model Link";

    
    private static final List<String> PREDEFINED_PATTERNS = List.of(
            "DBT_TMP",  // 12 chars – must precede RAW
            "RAW_DQ",  // 12 chars – must precede RAW
            "RAW_REJECTED",  // 12 chars – must precede RAW
            "RAW_PENDING",   // 11 chars – must precede RAW
            "REFINED_VW",    // 10 chars – must precede REFINED and VW
            "SEED_DIM",      //  8 chars – must precede DIM  (spec had trailing _)
            "CURATED",       //  7 chars – must precede CUR
            "STAGING",       //  7 chars
            "REFINED",       //  7 chars
            "SUBSET",        //  6 chars
            "RAW_VW",        //  6 chars – must precede RAW and VW
            "DIM_VW",        //  6 chars – must precede DIM and VW
            "INTG",          //  4 chars
            "FACT",          //  4 chars
            "STG",           //  3 chars
            "DIM",           //  3 chars
            "RAW",           //  3 chars
            "RFN",           //  3 chars
            "CUR",           //  3 chars – must follow CURATED
            "VW"             //  2 chars – standalone view prefix/suffix
    );

    
    private static final Map<String, String> LAYER_ALIASES = Map.of(
            "RFN",  "REFINED",
            "CUR",  "CURATED"
    );

    
    private static final String EXTERNAL_DATA_LAYER = "EXTERNAL DATA";


    
    private static final String PIPELINE_OUTPUTS_LAYER = "PIPELINE OUTPUTS";

    
    private static final Set<String> FORCED_LAST_LAYERS = Set.of(
            "CURATED VIEWS",
            EXTERNAL_DATA_LAYER,
            PIPELINE_OUTPUTS_LAYER
    );

    
    private final Map<String, Set<String>> validViewSuffixesPerContext = new LinkedHashMap<>();

    private final SolidatusModelService modelService;

    @Value("${solidatus.atomicModelId}")
    private String atomicModelId;

    @Value("${solidatus.displayModelName}")
    private String displayModelName;

    
    @Value("${solidatus.api.host}")
    private String solidatusHost;

    
    @Value("${solidatus.api.table-cat:}")
    private String tableCat;

    
    @Value("${solidatus.api.curated-schema-pattern:}")
    private String curatedSchemaPattern;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public DisplayModelBuilder(SolidatusModelService modelService) {
        this.modelService = modelService;
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    
    public void buildDisplayModel() {
        // Capture once – shared by the log file (via logback) and the CSV report
        final String runTimestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        log.info("========================================");
        log.info("DISPLAY MODEL BUILDER – START");
        log.info("  Atomic model ID : {}", atomicModelId);
        log.info("  Display model   : {}", displayModelName);
        log.info("========================================");

        try {
            // ── Step 1: retrieve the Atomic Model ────────────────────────────
            log.info("Fetching atomic model from Solidatus…");
            ModelData atomicModelData = modelService.retrieveModelById(atomicModelId);
            if (atomicModelData == null || atomicModelData.getData() == null) {
                log.error("Could not retrieve atomic model with ID '{}'. Aborting.", atomicModelId);
                return;
            }

            SimpleModelData atomicData      = atomicModelData.getData();
            Map<String, ModelEntityDataFlat> entities    = atomicData.getEntities();
            Map<String, ModelTransitionData> transitions = atomicData.getTransitions();
            List<String>                     roots       = atomicData.getRoots();

            if (entities == null || entities.isEmpty()) {
                log.error("Atomic model '{}' has no entities – aborting.", atomicModelId);
                return;
            }
            if (roots == null || roots.isEmpty()) {
                log.error("Atomic model '{}' has no root entities – aborting.", atomicModelId);
                return;
            }

            log.info("Atomic model loaded successfully.");
            log.info("  Entities    : {}", entities.size());
            log.info("  Transitions : {}", transitions == null ? 0 : transitions.size());
            log.info("  Roots (layers) : {}", roots.size());

            // Resolve the atomic model name (used as a property on every Display Model entity)
            final String resolvedModelName = (atomicModelData.getModel() != null
                    && atomicModelData.getModel().getName() != null)
                    ? atomicModelData.getModel().getName()
                    : atomicModelId;
            final String resolvedModelLink = solidatusHost.replaceAll("/$", "") + "/model/" + atomicModelId;
            log.info("  Atomic model name : {}", resolvedModelName);
            log.info("  Atomic model link : {}", resolvedModelLink);

            // ── Step 2: discover suffixes, apply OTHERS rules, ask for order ─
            // Pre-compute table-layer validity separately so thin table layers are still
            // routed to OTHERS even when a view-suffix layer of the same name exists.
            Map<String, Integer> tableLayerCounts = countTablesPerLayer(entities, roots);
            Set<String> validTableLayerNames = new HashSet<>();
            for (Map.Entry<String, Integer> tlEntry : tableLayerCounts.entrySet()) {
                if (!"OTHERS".equals(tlEntry.getKey()) && tlEntry.getValue() >= 3) {
                    validTableLayerNames.add(tlEntry.getKey());
                }
            }

            List<String> discoveredSuffixes = discoverSuffixes(entities, roots);

            // Use discovery order for initial layer creation.
            // The final left-to-right ordering is determined after all transitions
            // are remapped (Step 7 below), and the user is offered a chance to
            // fine-tune that finalized order before publishing.
            List<String> layerOrder = discoveredSuffixes;

            // ── Step 3: create an empty Display Model ────────────────────────
            SolidatusModel displayModel = new SolidatusModel(SOLUID);

            // atomicEntityId  →  new entity in the Display Model
            // (used later to remap transitions)
            Map<String, ModelEntityDataFlat> atomicIdToDisplay = new LinkedHashMap<>();

            // suffix (e.g. "RFN")  →  Layer entity in the Display Model
            // Populated in user-specified order before the table walk.
            Map<String, ModelEntityDataFlat> suffixToLayer = new LinkedHashMap<>();

            int tableCnt = 0, colCnt = 0,
                    toOthersNoPattern = 0, toOthersSmall = 0,
                    excludedBackup = 0, excludedDated = 0, excludedDeleted = 0;

            // ── Step 4: pre-create all Display Layers in discovery order ─────
            log.info("========================================");
            log.info("CREATING DISPLAY LAYERS (discovery order)");
            log.info("========================================");
            for (int li = 0; li < layerOrder.size(); li++) {
                String layerName = layerOrder.get(li);
                String lKey      = layerName.toUpperCase().replace(' ', '_');
                ModelEntityDataFlat layer = displayModel.createEntity(layerName, lKey, null);
                if (layer != null) {
                    layer.putPropertiesItem(SOLUID,            lKey);
                    layer.putPropertiesItem("Layer Name",      layerName);
                    layer.putPropertiesItem(SOURCE_MODEL_ID,   atomicModelId);
                    layer.putPropertiesItem(SOURCE_MODEL_NAME, resolvedModelName);
                    suffixToLayer.put(layerName, layer);
                    log.info("  [{}] Layer created: {}", li + 1, layerName);
                } else {
                    log.error("  ✗ Failed to create layer '{}'.", layerName);
                }
            }

            // ── Always ensure EXTERNAL DATA layer exists ─────────────────────
            // Objects whose TABLE_CAT property does not match solidatus.api.table-cat
            // are routed here regardless of their table-name-based layer resolution.
            if (!suffixToLayer.containsKey(EXTERNAL_DATA_LAYER)) {
                String edKey = EXTERNAL_DATA_LAYER.replace(' ', '_');
                ModelEntityDataFlat edLayer = displayModel.createEntity(EXTERNAL_DATA_LAYER, edKey, null);
                if (edLayer != null) {
                    edLayer.putPropertiesItem(SOLUID,            edKey);
                    edLayer.putPropertiesItem("Layer Name",      EXTERNAL_DATA_LAYER);
                    edLayer.putPropertiesItem(SOURCE_MODEL_ID,   atomicModelId);
                    edLayer.putPropertiesItem(SOURCE_MODEL_NAME, resolvedModelName);
                    suffixToLayer.put(EXTERNAL_DATA_LAYER, edLayer);
                    log.info("  [+] Layer pre-created: {} (for TABLE_CAT mismatches)", EXTERNAL_DATA_LAYER);
                }
            }

            // ── Ensure PIPELINE OUTPUTS layer exists when configured ─────────
            boolean pipelineOutputsEnabled = curatedSchemaPattern != null && !curatedSchemaPattern.isBlank();
            if (pipelineOutputsEnabled && !suffixToLayer.containsKey(PIPELINE_OUTPUTS_LAYER)) {
                String poKey = PIPELINE_OUTPUTS_LAYER.replace(' ', '_');
                ModelEntityDataFlat poLayer = displayModel.createEntity(PIPELINE_OUTPUTS_LAYER, poKey, null);
                if (poLayer != null) {
                    poLayer.putPropertiesItem(SOLUID,            poKey);
                    poLayer.putPropertiesItem("Layer Name",      PIPELINE_OUTPUTS_LAYER);
                    poLayer.putPropertiesItem(SOURCE_MODEL_ID,   atomicModelId);
                    poLayer.putPropertiesItem(SOURCE_MODEL_NAME, resolvedModelName);
                    suffixToLayer.put(PIPELINE_OUTPUTS_LAYER, poLayer);
                    log.info("  [+] Layer pre-created: {} (schema pattern: '{}')",
                             PIPELINE_OUTPUTS_LAYER, curatedSchemaPattern);
                }
            }

            // ── Pre-compute per-entity outgoing transition counts in the atomic model ─
            // Used by the PIPELINE OUTPUTS routing rule: an object qualifies only when
            // it has zero outgoing lineage transitions (nothing downstream consumes it).
            Map<String, Integer> atomicOutgoingCount = new HashMap<>();
            if (transitions != null) {
                for (ModelTransitionData t : transitions.values()) {
                    if (t.getSource() != null) {
                        atomicOutgoingCount.merge(t.getSource(), 1, Integer::sum);
                    }
                }
            }

            // ── Step 5: walk the Atomic Model tree ───────────────────────────
            //    Depth-1 children of every root  =  objects / tables
            //    Depth-2 children                =  attributes / columns
            log.info("========================================");
            log.info("BUILDING DISPLAY MODEL STRUCTURE");
            log.info("========================================");

            for (String rootId : roots) {
                ModelEntityDataFlat atomicLayer = entities.get(rootId);
                if (atomicLayer == null) {
                    log.warn("Root ID '{}' not found in entities map – skipping.", rootId);
                    continue;
                }

                String atomicLayerName = safeGetName(atomicLayer);
                log.info("Processing atomic layer '{}' (id={})…", atomicLayerName, rootId);

                // Pre-compute VIEWS context for this atomic layer.
                // Non-null means views inside it will be suffix-routed to a dedicated layer
                // (when > 3 views share a suffix) or fall back to the VIEWS context layer.
                String atomicLayerViewsContext = extractLayerFromAtomicLayerContext(atomicLayerName);
                if (atomicLayerViewsContext != null) {
                    log.info("  ↳ VIEWS domain detected in '{}': views may be suffix-routed or fall back to '{}'",
                             atomicLayerName, atomicLayerViewsContext);
                }

                List<String> tableIds = atomicLayer.getChildren();
                if (tableIds == null || tableIds.isEmpty()) {
                    log.info("  ↳ Layer '{}' has no children – skipping.", atomicLayerName);
                    continue;
                }

                for (String tableId : tableIds) {
                    ModelEntityDataFlat atomicTable = entities.get(tableId);
                    if (atomicTable == null) continue;

                    String tableName = safeGetName(atomicTable);
                    if (tableName.isEmpty()) continue;

                    // ── Exclude backup / dated / deleted tables ──────────────
                    String upperName = tableName.toUpperCase().trim();
                    if (isBackupTable(upperName)) {
                        log.info("  ⊘ SKIPPED [BACKUP] : {}", tableName);
                        excludedBackup++;
                        continue;
                    }
                    if (isDatedTable(upperName)) {
                        log.info("  ⊘ SKIPPED [DATED]  : {}", tableName);
                        excludedDated++;
                        continue;
                    }
                    if (isDeletedTable(upperName)) {
                        log.info("  ⊘ SKIPPED [DELETED]: {}", tableName);
                        excludedDeleted++;
                        continue;
                    }

                    // ── Resolve Display-Model layer name ────────────────────
                    // For views in a VIEWS atomic layer: apply view-specific suffix routing.
                    //   • DQ_ views   → last segment (e.g. DQ_USER_RESULTS → RESULTS)
                    //   • Other views → same extractLayer() logic as tables
                    //   • Suffix group > 3 views → dedicated suffix layer
                    //   • Suffix group ≤ 3 views → falls back to the VIEWS context layer
                    // For regular tables: use predefined-pattern / fallback resolution.
                    String  layerName;
                    boolean viewSuffixRouted = false; // true ⟹ bypass the thin-layer → OTHERS check

                    if (atomicLayerViewsContext != null) {
                        // ── VIEWS atomic layer: view suffix routing ──────────
                        String viewSuffixLayer = extractViewSuffixLayer(tableName);
                        Set<String> validSuffixes = validViewSuffixesPerContext
                                .getOrDefault(atomicLayerViewsContext, Set.of());

                        if (viewSuffixLayer != null && validSuffixes.contains(viewSuffixLayer)) {
                            layerName        = viewSuffixLayer;
                            viewSuffixRouted = true;
                            log.info("  ✚ View '{}' → suffix layer '{}' (VIEWS context: '{}')",
                                     tableName, viewSuffixLayer, atomicLayerViewsContext);
                        } else {
                            layerName = atomicLayerViewsContext;  // fallback to VIEWS layer
                            if (viewSuffixLayer != null) {
                                log.info("  ⟳ View '{}': suffix '{}' thin (≤ 3 views) → stays in '{}'",
                                         tableName, viewSuffixLayer, atomicLayerViewsContext);
                            }
                        }
                    } else {
                        // ── Regular table: predefined-pattern / fallback resolution ──
                        layerName = extractLayer(tableName);
                    }

                    if (!viewSuffixRouted) {
                        if (layerName == null) {
                            // No predefined pattern and no atomic-layer context → OTHERS
                            log.info("  ⟳ No pattern match: '{}' → OTHERS", tableName);
                            toOthersNoPattern++;
                            layerName = "OTHERS";
                        } else if (!validTableLayerNames.contains(layerName)) {
                            // Layer had < 3 tables → OTHERS
                            log.info("  ⟳ Thin layer '{}': '{}' → OTHERS", layerName, tableName);
                            toOthersSmall++;
                            layerName = "OTHERS";
                        }
                    }

                    // ── TABLE_CAT check: route to EXTERNAL DATA if mismatch ──
                    // When solidatus.api.table-cat is configured, any object whose
                    // TABLE_CAT property value does not match (case-insensitive)
                    // is placed in EXTERNAL DATA regardless of name-based routing.
                    if (tableCat != null && !tableCat.isBlank()) {
                        Map<String, String> tableProps = atomicTable.getProperties();
                        if (tableProps != null) {
                            String entityTableCat = tableProps.get("TABLE_CAT");
                            if (entityTableCat != null
                                    && !entityTableCat.equalsIgnoreCase(tableCat)) {
                                log.info("  ⟳ TABLE_CAT mismatch: '{}' → TABLE_CAT='{}' (expected '{}') → {}",
                                         tableName, entityTableCat, tableCat, EXTERNAL_DATA_LAYER);
                                layerName = EXTERNAL_DATA_LAYER;
                            }
                        }
                    }

                    Map<String, String> currentTableProps = atomicTable.getProperties();
                    String currentSchema = currentTableProps == null ? null
                            : currentTableProps.getOrDefault("TABLE_SCHEMA",
                                    currentTableProps.getOrDefault("TABLE_SCHEM",
                                            currentTableProps.get("schema")));

                    // ── PIPELINE OUTPUTS check: terminal curated objects ─────
                    // Object qualifies when its schema matches curatedSchemaPattern
                    // AND it has zero outgoing transitions in the Atomic Model.
                    //
                    // IMPORTANT: lineage transitions almost always originate from a
                    // table's COLUMNS (attributes), not the table entity itself, so
                    // the table's own outgoing count is nearly always 0 regardless of
                    // whether it truly is terminal. We must therefore also sum the
                    // outgoing counts of every column under this table – otherwise
                    // curated tables that legitimately feed downstream consumers get
                    // incorrectly classified as terminal and routed here.
                    if (pipelineOutputsEnabled
                            && !EXTERNAL_DATA_LAYER.equals(layerName)
                            && currentSchema != null
                            && currentSchema.toUpperCase().contains(curatedSchemaPattern.toUpperCase())) {
                        int outCount = atomicOutgoingCount.getOrDefault(tableId, 0);
                        if (atomicTable.getId() != null) {
                            outCount = Math.max(outCount, atomicOutgoingCount.getOrDefault(atomicTable.getId(), 0));
                        }
                        List<String> atomicColIds = atomicTable.getChildren();
                        if (outCount == 0 && atomicColIds != null) {
                            for (String colId : atomicColIds) {
                                outCount += atomicOutgoingCount.getOrDefault(colId, 0);
                                ModelEntityDataFlat atomicColForCheck = entities.get(colId);
                                if (atomicColForCheck != null && atomicColForCheck.getId() != null) {
                                    outCount += atomicOutgoingCount.getOrDefault(atomicColForCheck.getId(), 0);
                                }
                                if (outCount > 0) break;
                            }
                        }
                        if (outCount == 0) {
                            log.info("  ⟳ Terminal curated object (schema='{}', outgoing=0 incl. columns): '{}' → {}",
                                     currentSchema, tableName, PIPELINE_OUTPUTS_LAYER);
                            layerName = PIPELINE_OUTPUTS_LAYER;
                        }
                    }

                    // ── Look up the pre-created Display Layer for this layer name ─
                    ModelEntityDataFlat displayLayer = suffixToLayer.get(layerName);
                    if (displayLayer == null) {
                        // Fallback: layer appeared in atomic model but was not in
                        // the user-confirmed order (should not happen normally)
                        String lKey = layerName.toUpperCase().replace(' ', '_');
                        displayLayer = displayModel.createEntity(layerName, lKey, null);
                        if (displayLayer != null) {
                            displayLayer.putPropertiesItem(SOLUID,            lKey);
                            displayLayer.putPropertiesItem("Layer Name",      layerName);
                            displayLayer.putPropertiesItem(SOURCE_MODEL_ID,   atomicModelId);
                            displayLayer.putPropertiesItem(SOURCE_MODEL_NAME, resolvedModelName);
                            suffixToLayer.put(layerName, displayLayer);
                            log.warn("  ↳ Layer '{}' created on-the-fly (was not in user order).", layerName);
                        } else {
                            log.error("  ✗ Failed to create fallback layer '{}'.", layerName);
                        }
                    }
                    if (displayLayer == null) continue;

                    // ── Get-or-create the Table (Object) under the Layer ─────
                    String tKey = (layerName + "/" + tableName).toUpperCase().replace(' ', '_');
                    ModelEntityDataFlat displayTable = displayModel.getEntityByKey(tKey);
                    if (displayTable == null) {
                        displayTable = displayModel.createEntity(tableName, tKey, displayLayer);
                        if (displayTable == null) {
                            log.error("  ✗ Failed to create table entity '{}'.", tableName);
                            continue;
                        }
                        copyProperties(atomicTable, displayTable, tKey, resolvedModelName, resolvedModelLink);
                        tableCnt++;
                        log.info("  ✚ Created Table (Object): {}  →  Layer: {}", tableName, layerName);
                    }

                    // Map both the map-key and the entity's own ID to the display entity
                    atomicIdToDisplay.put(tableId, displayTable);
                    if (atomicTable.getId() != null) {
                        atomicIdToDisplay.put(atomicTable.getId(), displayTable);
                    }

                    // ── Create Columns (Attributes) under the Table ──────────
                    List<String> colIds = atomicTable.getChildren();
                    if (colIds != null) {
                        for (String colId : colIds) {
                            ModelEntityDataFlat atomicCol = entities.get(colId);
                            if (atomicCol == null) continue;

                            String colName = safeGetName(atomicCol);
                            if (colName.isEmpty()) continue;

                            String cKey = (tKey + "/" + colName).toUpperCase().replace(" ", "_");
                            ModelEntityDataFlat displayCol = displayModel.getEntityByKey(cKey);
                            if (displayCol == null) {
                                displayCol = displayModel.createEntity(colName, cKey, displayTable);
                                if (displayCol == null) {
                                    log.warn("    ✗ Failed to create column entity '{}'.", colName);
                                    continue;
                                }
                                copyProperties(atomicCol, displayCol, cKey, resolvedModelName, resolvedModelLink);
                                colCnt++;
                                log.debug("    ✚ Created Column (Attribute): {}", colName);
                            }

                            // Map both the map-key and the entity's own ID
                            atomicIdToDisplay.put(colId, displayCol);
                            if (atomicCol.getId() != null) {
                                atomicIdToDisplay.put(atomicCol.getId(), displayCol);
                            }
                        }
                    }
                } // end tables loop
            } // end roots loop

            log.info("========================================");
            log.info("ENTITY BUILD SUMMARY");
            log.info("  Suffix Layers created             : {}", suffixToLayer.size());
            log.info("  Layer order                       : {}", String.join(" → ", layerOrder));
            log.info("  Tables created                    : {}", tableCnt);
            log.info("  Columns created                   : {}", colCnt);
            log.info("  → placed in OTHERS (no pattern)   : {}", toOthersNoPattern);
            log.info("  → placed in OTHERS (thin layer)   : {}", toOthersSmall);
            log.info("  Tables excluded (backup)          : {}", excludedBackup);
            log.info("  Tables excluded (dated)           : {}", excludedDated);
            log.info("  Tables excluded (deleted)         : {}", excludedDeleted);
            log.info("========================================");

            // ── Step 6: remap transitions (lineage) ──────────────────────────
            int transMapped = 0, transSkipped = 0;
            if (transitions != null && !transitions.isEmpty()) {
                log.info("Remapping {} transition(s) from atomic model…", transitions.size());
                for (ModelTransitionData atomicTrans : transitions.values()) {
                    String srcId = atomicTrans.getSource();
                    String tgtId = atomicTrans.getTarget();
                    if (srcId == null || tgtId == null) {
                        transSkipped++;
                        continue;
                    }

                    ModelEntityDataFlat displaySrc = atomicIdToDisplay.get(srcId);
                    ModelEntityDataFlat displayTgt = atomicIdToDisplay.get(tgtId);

                    if (displaySrc != null && displayTgt != null) {
                        ModelTransitionData newTrans =
                                displayModel.createTransitionIfNotExist(displaySrc, displayTgt);
                        if (newTrans != null && atomicTrans.getProperties() != null) {
                            atomicTrans.getProperties().forEach(newTrans::putPropertiesItem);
                        }
                        transMapped++;
                    } else {
                        log.debug("  Skipping transition {} → {} (entity not in display model scope).",
                                  srcId, tgtId);
                        transSkipped++;
                    }
                }
                log.info("  Transitions remapped  : {}", transMapped);
                log.info("  Transitions skipped   : {} (entity not in scope)", transSkipped);
            } else {
                log.info("No transitions found in atomic model – skipping transition remapping.");
            }

            // ── Step 7: reorder layers by transition flow ─────────────────────
            log.info("========================================");
            log.info("REORDERING LAYERS BY TRANSITION FLOW");
            log.info("========================================");
            List<LayerTransitionStats> layerStats = reorderLayersByTransitions(displayModel, suffixToLayer);
            List<String> autoOrder = new ArrayList<>();
            if (!layerStats.isEmpty()) {
                log.info("  Layer ordering based on greedy topological sort (maximises left-to-right flow):");
                for (LayerTransitionStats ls : layerStats) {
                    log.info("    {} : In={}, Out={}, Net={}",
                             ls.layerName, ls.incoming, ls.outgoing, ls.net());
                }
                for (LayerTransitionStats ls : layerStats) autoOrder.add(ls.layerName);
                log.info("  Transition-based layer order (left → right): {}", String.join(" → ", autoOrder));
            } else {
                log.info("  No transition data available – layer order unchanged.");
                for (String rootId : displayModel.getSimpleModelData().getRoots()) {
                    ModelEntityDataFlat le = displayModel.getSimpleModelData().getEntities().get(rootId);
                    if (le != null) autoOrder.add(safeGetName(le));
                }
            }

            // ── Step 8: let user optionally fine-tune the finalized order ────
            // The automated transition-based order is presented to the user.
            // Pressing ENTER accepts it as-is; typing a custom sequence overrides it.
            if (!autoOrder.isEmpty()) {
                List<String> finalOrder = promptLayerOrder(autoOrder);

                if (!finalOrder.equals(autoOrder)) {
                    // User adjusted the order – rebuild the roots list accordingly
                    List<String> newRoots = new ArrayList<>();
                    for (String ln : finalOrder) {
                        ModelEntityDataFlat le = suffixToLayer.get(ln);
                        if (le != null && le.getId() != null) newRoots.add(le.getId());
                    }
                    Set<String> covered = new HashSet<>(newRoots);
                    for (String rootId : displayModel.getSimpleModelData().getRoots()) {
                        if (!covered.contains(rootId)) newRoots.add(rootId);
                    }
                    displayModel.getSimpleModelData().setRoots(newRoots);
                    log.info("  User-adjusted layer order: {}", String.join(" → ", finalOrder));
                } else {
                    log.info("  Transition-based order accepted unchanged.");
                }
            }

            // ── Step 9: sort all children alphabetically within every layer ──
            // Objects (tables), groups, and attributes are sorted in natural
            // ascending order so the canvas presents a consistent, predictable
            // layout regardless of the order in which entities were created.
            // orderAllChildrenAlphabetically(layer, true) recurses into every
            // level of the hierarchy (objects → attributes).
            log.info("========================================");
            log.info("SORTING ENTITIES ALPHABETICALLY");
            log.info("========================================");
            for (Map.Entry<String, ModelEntityDataFlat> entry : suffixToLayer.entrySet()) {
                ModelEntityDataFlat layerEntity = entry.getValue();
                if (layerEntity != null) {
                    displayModel.orderAllChildrenAlphabetically(layerEntity, true);
                    log.info("  ✓ Sorted children of layer '{}'", entry.getKey());
                }
            }

            // ── Step 10: generate object analysis CSV report ─────────────────
            generateObjectAnalysisReport(
                    displayModel, suffixToLayer,
                    resolvedModelName, layerStats,
                    runTimestamp);

            // ── Step 11: publish the Display Model ───────────────────────────
            log.info("========================================");
            log.info("PUBLISHING DISPLAY MODEL: {}", displayModelName);
            log.info("========================================");

            // ── Add the link-icon Display Rule for Object entities ────────
            displayModel.addDisplayRule(buildObjectLinkDisplayRule());
            log.info("Display rule added: link icon on Object entities → '{}'", SOURCE_MODEL_LINK);

            modelService.saveModel(
                    displayModel.getSimpleModelData(),
                    displayModelName,
                    new Comparator().property(SOLUID));
            log.info("Display model '{}' published successfully!", displayModelName);

        } catch (Exception e) {
            log.error("Unexpected error while building display model.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    
    private List<String> discoverSuffixes(Map<String, ModelEntityDataFlat> entities,
                                          List<String> roots) {
        Map<String, Integer> counts       = countTablesPerLayer(entities, roots);
        Map<String, Map<String, Integer>> viewSuffixMap = computeViewSuffixCounts(entities, roots);

        log.info("========================================");
        log.info("LAYER TABLE COUNTS (pre-build analysis)");
        log.info("========================================");

        List<String> ordered   = new ArrayList<>();
        boolean      hasOthers = false;

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String layerName = entry.getKey();
            int    cnt       = entry.getValue();

            if ("OTHERS".equals(layerName)) {
                // Tables with no predefined pattern – always OTHERS
                hasOthers = true;
                log.info("  {} : {} table(s)  [→ OTHERS – no predefined pattern]", layerName, cnt);
            } else if (cnt < 3) {
                // Thin layer – merge into OTHERS
                hasOthers = true;
                log.info("  {} : {} table(s)  [→ OTHERS – below threshold of 3]", layerName, cnt);
            } else {
                ordered.add(layerName);
                log.info("  {} : {} table(s)  [✓ valid layer]", layerName, cnt);
            }
        }

        if (hasOthers) {
            ordered.add("OTHERS");   // always appended last in discovery order
            log.info("  OTHERS : (merged from thin / unmatched tables)");
        }

        // ── View suffix layers ─────────────────────────────────────────────────
        // For each VIEWS atomic-layer context (e.g. "CURATED VIEWS"), determine which
        // view suffix groups have > 3 views and therefore qualify for a dedicated layer.
        validViewSuffixesPerContext.clear();
        Set<String> newViewSuffixLayers = new LinkedHashSet<>();

        if (!viewSuffixMap.isEmpty()) {
            log.info("========================================");
            log.info("VIEW SUFFIX COUNTS (per VIEWS context)");
            log.info("========================================");

            for (Map.Entry<String, Map<String, Integer>> ctxEntry : viewSuffixMap.entrySet()) {
                String viewsContext               = ctxEntry.getKey();
                Map<String, Integer> suffixCounts = ctxEntry.getValue();

                log.info("  VIEWS context: '{}'", viewsContext);
                Set<String> validSuffixes = new LinkedHashSet<>();

                for (Map.Entry<String, Integer> se : suffixCounts.entrySet()) {
                    String suffixLayer = se.getKey();
                    int    cnt         = se.getValue();

                    if (cnt > 3) {
                        validSuffixes.add(suffixLayer);
                        newViewSuffixLayers.add(suffixLayer);
                        log.info("    {} : {} view(s)  [✓ valid view suffix layer (> 3)]", suffixLayer, cnt);
                    } else {
                        log.info("    {} : {} view(s)  [→ stays in '{}' – at or below threshold of 3]",
                                 suffixLayer, cnt, viewsContext);
                    }
                }

                validViewSuffixesPerContext.put(viewsContext, validSuffixes);
                if (!validSuffixes.isEmpty()) {
                    log.info("    → Valid suffixes for '{}': {}", viewsContext, validSuffixes);
                }
            }

            // Append view suffix layers not already in the ordered list
            for (String vsl : newViewSuffixLayers) {
                if (!ordered.contains(vsl)) {
                    ordered.add(vsl);
                    log.info("  → New view suffix layer appended to layer list: '{}'", vsl);
                } else {
                    log.info("  → View suffix layer '{}' already present in layer list.", vsl);
                }
            }

            log.info("========================================");
        }

        return ordered;
    }

    
    private Map<String, Integer> countTablesPerLayer(
            Map<String, ModelEntityDataFlat> entities, List<String> roots) {

        Map<String, Integer> counts = new LinkedHashMap<>();

        for (String rootId : roots) {
            ModelEntityDataFlat layer = entities.get(rootId);
            if (layer == null) continue;
            List<String> tableIds = layer.getChildren();
            if (tableIds == null) continue;

            // If the atomic layer itself is a VIEWS domain (e.g. "CUR.VIEWS", "RFN.VIEWS")
            // every table inside it goes to the corresponding VIEWS display layer,
            // regardless of the individual table name.
            String atomicLayerContext = extractLayerFromAtomicLayerContext(safeGetName(layer));

            for (String tableId : tableIds) {
                ModelEntityDataFlat table = entities.get(tableId);
                if (table == null) continue;
                String name = safeGetName(table);
                if (name.isEmpty()) continue;

                String upper = name.toUpperCase().trim();
                if (isBackupTable(upper) || isDatedTable(upper) || isDeletedTable(upper)) continue;

                // Atomic layer context takes priority over table-name-based resolution
                String layerName = (atomicLayerContext != null)
                        ? atomicLayerContext
                        : extractLayer(name);
                counts.merge(layerName != null ? layerName : "OTHERS", 1, Integer::sum);
            }
        }
        return counts;
    }

    
    private Map<String, Map<String, Integer>> computeViewSuffixCounts(
            Map<String, ModelEntityDataFlat> entities, List<String> roots) {

        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();

        for (String rootId : roots) {
            ModelEntityDataFlat atomicLayer = entities.get(rootId);
            if (atomicLayer == null) continue;

            String viewsContext = extractLayerFromAtomicLayerContext(safeGetName(atomicLayer));
            if (viewsContext == null) continue;  // not a VIEWS atomic layer

            List<String> viewIds = atomicLayer.getChildren();
            if (viewIds == null) continue;

            Map<String, Integer> suffixCounts =
                    result.computeIfAbsent(viewsContext, k -> new LinkedHashMap<>());

            for (String viewId : viewIds) {
                ModelEntityDataFlat view = entities.get(viewId);
                if (view == null) continue;
                String viewName = safeGetName(view);
                if (viewName.isEmpty()) continue;

                String upper = viewName.toUpperCase().trim();
                if (isBackupTable(upper) || isDatedTable(upper) || isDeletedTable(upper)) continue;

                String suffixLayer = extractViewSuffixLayer(viewName);
                if (suffixLayer != null) {
                    suffixCounts.merge(suffixLayer, 1, Integer::sum);
                }
                // null → view stays in the VIEWS context layer; no separate count needed
            }
        }
        return result;
    }

    
    private List<String> promptLayerOrder(List<String> discovered) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     DISPLAY MODEL – FINAL LAYER ORDER REVIEW                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("  The layer order below has been determined automatically by analysing");
        System.out.println("  lineage transitions so that the majority of data flow goes left → right.");
        System.out.println();
        for (int i = 0; i < discovered.size(); i++) {
            System.out.printf("    [%d] %s%n", i + 1, discovered.get(i));
        }
        System.out.println();
        System.out.printf("  Transition-based order: %s%n", String.join(", ", discovered));
        System.out.println();
        System.out.println("  Optionally adjust the layer order (or press ENTER to accept as-is):");
        System.out.println("    • Comma-separated numbers  e.g.  2,3,1");
        System.out.println("    • Comma-separated names    e.g.  CUR,MKT,RFN");
        System.out.println("    • Press ENTER to keep the transition-based order");
        System.out.println();
        System.out.print("  > ");
        System.out.flush();

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line = reader.readLine();

            if (line == null || line.trim().isEmpty()) {
                System.out.println("  → Keeping transition-based order.");
                System.out.println();
                return new ArrayList<>(discovered);
            }

            String[]      tokens     = line.trim().split("\\s*,\\s*");
            boolean       allNumeric = Arrays.stream(tokens).allMatch(t -> t.matches("\\d+"));
            List<String>  ordered    = new ArrayList<>();
            Set<String>   addedUpper = new HashSet<>();

            if (allNumeric) {
                // ── numeric input: user typed index numbers ─────────────────
                for (String token : tokens) {
                    int idx = Integer.parseInt(token.trim()) - 1;
                    if (idx < 0 || idx >= discovered.size()) {
                        System.out.printf("  ⚠  Index '%s' is out of range – ignoring.%n", token.trim());
                        continue;
                    }
                    String s = discovered.get(idx);
                    if (addedUpper.add(s.toUpperCase())) {
                        ordered.add(s);
                    }
                }
            } else {
                // ── name-based input: user typed suffix names ────────────────
                Map<String, String> upperToOrig = new LinkedHashMap<>();
                for (String s : discovered) {
                    upperToOrig.put(s.toUpperCase(), s);
                }
                for (String token : tokens) {
                    String key = token.trim().toUpperCase();
                    if (!upperToOrig.containsKey(key)) {
                        System.out.printf("  ⚠  Suffix '%s' not found in atomic model – ignoring.%n",
                                token.trim());
                        continue;
                    }
                    if (addedUpper.add(key)) {
                        ordered.add(upperToOrig.get(key));
                    }
                }
            }

            // Append any discovered suffixes the user omitted (keep none behind)
            for (String s : discovered) {
                if (addedUpper.add(s.toUpperCase())) {
                    ordered.add(s);
                    System.out.printf("  ℹ  Suffix '%s' was not mentioned – appended at the end.%n", s);
                }
            }

            System.out.printf("%n  → Final layer order: %s%n%n", String.join(", ", ordered));            return ordered;

        } catch (Exception e) {
            log.warn("Could not read user input ({}). Keeping discovery order.", e.getMessage());
            System.out.println();
            return new ArrayList<>(discovered);
        }
    }

    // -------------------------------------------------------------------------

    
    private String extractLayer(String tableName) {
        if (tableName == null || tableName.isEmpty()) return null;
        // ── Normalise table name ──────────────────────────────────────────────
        // Some source systems store table names with leading or trailing
        // backslashes (e.g. "dbt_tmp\", "\stg_table").  Strip those artefact
        // characters so "dbt_tmp\" is treated identically to "dbt_tmp" and lands
        // in the same layer.  Forward slashes are stripped for the same reason.
        String upper = tableName.toUpperCase().trim()
                                .replaceAll("^[/\\\\]+|[/\\\\]+$", "")
                                .trim();
        if (upper.isEmpty()) return null;

        for (String pattern : PREDEFINED_PATTERNS) {
            // Prefix match: TABLE_NAME starts with PATTERN + "_"  (or equals PATTERN exactly)
            if (upper.startsWith(pattern + "_") || upper.equals(pattern)) {
                String name = normalizeLayerAlias(patternToLayerName(pattern));
                return isValidLayerName(name) ? name : null;
            }
            // Suffix match: TABLE_NAME ends with "_" + PATTERN
            if (upper.endsWith("_" + pattern)) {
                String name = normalizeLayerAlias(patternToLayerName(pattern));
                return isValidLayerName(name) ? name : null;
            }
        }

        // ── Fallback: segment after the last underscore ──────────────────────
        // Enables dynamic layers for suffixes not in PREDEFINED_PATTERNS
        // (e.g. VALUES, RESULTS, LOOKUP …).
        // The thin-layer threshold in discoverSuffixes() ensures that a dynamic
        // suffix only becomes its own layer when ≥ 3 tables share it; otherwise
        // the tables are still merged into OTHERS automatically.
        int idx = upper.lastIndexOf('_');
        if (idx >= 0 && idx < upper.length() - 1) {
            String name = normalizeLayerAlias(upper.substring(idx + 1));
            return isValidLayerName(name) ? name : null;
        }

        // No underscore at all → no layer can be determined → OTHERS
        return null;
    }

    
    private boolean isValidLayerName(String layerName) {
        if (layerName == null || layerName.isEmpty()) return false;
        // Single character
        if (layerName.length() == 1) return false;
        // Digits only (e.g. date stamps, version numbers)
        if (layerName.matches("[0-9]+")) return false;
        // Contains non-alphanumeric, non-space characters (e.g. backslash, slash,
        // special chars that were not stripped during table-name normalisation).
        // Acts as a defence-in-depth guard: "TMP\" or "\STG" must never become
        // a layer name.
        if (layerName.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch) && ch != ' ')) {
            return false;
        }
        // Alphanumeric mix: has both letters AND digits but no whitespace
        // (e.g. C1, TMP2, A2B3 — typical artefact suffixes)
        boolean hasLetter = layerName.chars().anyMatch(Character::isLetter);
        boolean hasDigit  = layerName.chars().anyMatch(Character::isDigit);
        if (hasLetter && hasDigit && !layerName.contains(" ")) return false;
        return true;
    }

    
    private String normalizeLayerAlias(String layerName) {
        if (layerName == null) return null;
        return LAYER_ALIASES.getOrDefault(layerName, layerName);
    }

    
    private String extractLayerFromAtomicLayerContext(String atomicLayerName) {
        if (atomicLayerName == null || atomicLayerName.isEmpty()) return null;
        String upper = atomicLayerName.toUpperCase().trim();

        // Standalone VIEWS layer (no prefix)
        if (upper.equals("VIEWS")) return "VIEWS";

        // "PREFIX.VIEWS" pattern  (e.g. CUR.VIEWS, RFN.VIEWS, RAW.VIEWS)
        if (upper.endsWith(".VIEWS")) {
            String prefix = upper.substring(0, upper.length() - ".VIEWS".length()).trim();
            if (prefix.isEmpty()) return "VIEWS";
            return normalizeLayerAlias(prefix) + " VIEWS";
        }

        return null;  // not a VIEWS layer → fall through to table-name resolution
    }

    
    private String extractViewSuffixLayer(String viewName) {
        if (viewName == null || viewName.isEmpty()) return null;
        String upper = viewName.toUpperCase().trim();

        // ── DQ_ static rule ────────────────────────────────────────────────────
        // Views starting with DQ_ always use the last segment as their layer name.
        if (upper.startsWith("DQ_")) {
            int idx = upper.lastIndexOf('_');
            if (idx > 2 && idx < upper.length() - 1) {   // idx > 2 to skip the 'DQ' prefix itself
                String name = normalizeLayerAlias(upper.substring(idx + 1));
                return isValidLayerName(name) ? name : null;
            }
            return null;  // DQ_ with no further underscore → fall back to VIEWS layer
        }

        // ── General suffix logic (identical to table resolution) ────────────────
        String extracted = extractLayer(viewName);

        // VIEWS-type results (standalone "VIEWS" or "X VIEWS") are filtered out:
        // VW-suffixed views inside a VIEWS layer belong to that VIEWS layer, not to
        // a separate VIEWS sub-layer.
        if (extracted != null && (extracted.equals("VIEWS") || extracted.endsWith(" VIEWS"))) {
            return null;
        }

        return extracted;
    }

    
    private String patternToLayerName(String pattern) {
        if ("VW".equals(pattern)) {
            return "VIEWS";
        }
        if (pattern.endsWith("_VW")) {
            // e.g. REFINED_VW → "REFINED VIEWS",  DIM_VW → "DIM VIEWS"
            return pattern.substring(0, pattern.length() - 3).replace('_', ' ') + " VIEWS";
        }
        // e.g. RAW_PENDING → "RAW PENDING",  SEED_DIM → "SEED DIM",  RFN → "RFN"
        return pattern.replace('_', ' ');
    }

    // ── Table-exclusion predicates ─────────────────────────────────────────
    // Each method receives the table name already normalised to UPPER CASE.
    // They mirror the Excel formulas supplied by the business.

    
    private boolean isBackupTable(String upper) {
        return upper.endsWith("BACKUP")   // e.g. TABLE_BACKUP, TABLEBACKUP
            || upper.endsWith("BKP")      // e.g. TABLE_BKP
            || upper.startsWith("BKP_")   // e.g. BKP_TABLE
            || upper.contains("_BKP");    // e.g. TABLE_BKP_2023, contains _BKP anywhere
    }

    
    private boolean isDatedTable(String upper) {
        int len = upper.length();

        // last 4 chars → try to parse as positive int
        if (len >= 4) {
            try {
                if (Integer.parseInt(upper.substring(len - 4)) > 0) return true;
            } catch (NumberFormatException ignored) { /* not numeric */ }
        }

        if (len >= 8) {
            String last8 = upper.substring(len - 8);

            // last 8 chars → try to parse as positive int (e.g. "20230101")
            try {
                if (Integer.parseInt(last8) > 0) return true;
            } catch (NumberFormatException ignored) { /* not numeric */ }

            // last 8 chars with underscores removed → try to parse (e.g. "2023_01" → "202301")
            try {
                if (Integer.parseInt(last8.replace("_", "")) > 0) return true;
            } catch (NumberFormatException ignored) { /* not numeric */ }
        }

        return false;
    }

    
    private boolean isDeletedTable(String upper) {
        return upper.endsWith("_DEL")   // e.g. TABLE_DEL
            || upper.startsWith("DEL_"); // e.g. DEL_TABLE
    }

    
    private String safeGetName(ModelEntityDataFlat entity) {
        if (entity == null) return "";
        String n = entity.getName();
        return n == null ? "" : n.trim();
    }

    
    private void copyProperties(ModelEntityDataFlat from, ModelEntityDataFlat to,
                                String soluidValue, String srcModelName, String srcModelLink) {
        // 1. Copy every original property from the atomic entity first
        if (from.getProperties() != null) {
            from.getProperties().forEach(to::putPropertiesItem);
        }
        // 2. Overwrite / add the stable SOLUID comparator key
        to.putPropertiesItem(SOLUID, soluidValue);
        // 3. Record the original atomic entity ID for traceability
        if (from.getId() != null) {
            to.putPropertiesItem(ATOMIC_ORIGIN_ID, from.getId());
        }
        // 4. Stamp the source-model provenance on every entity ──────────────
        //    These three properties answer "where was this object imported from?"
        to.putPropertiesItem(SOURCE_MODEL_ID,   atomicModelId);   // e.g. "6a41fb01001f297ff2486950"
        to.putPropertiesItem(SOURCE_MODEL_NAME, srcModelName);     // e.g. "Test Import Atomic Model"
        to.putPropertiesItem(SOURCE_MODEL_LINK, srcModelLink);     // e.g. "https://…/model/6a41fb01…"
    }

    // -------------------------------------------------------------------------
    // Layer transition-based reordering
    // -------------------------------------------------------------------------

    
    static final class LayerTransitionStats {
        final String layerName;
        final String entityId;
        final int    incoming;
        final int    outgoing;

        LayerTransitionStats(String layerName, String entityId, int incoming, int outgoing) {
            this.layerName = layerName;
            this.entityId  = entityId;
            this.incoming  = incoming;
            this.outgoing  = outgoing;
        }

        
        int net() { return outgoing - incoming; }
    }

    
    List<LayerTransitionStats> reorderLayersByTransitions(
            SolidatusModel displayModel,
            Map<String, ModelEntityDataFlat> suffixToLayer) {

        SimpleModelData smd = displayModel.getSimpleModelData();
        if (smd == null) return List.of();

        Map<String, ModelEntityDataFlat> entities    = smd.getEntities();
        Map<String, ModelTransitionData> transitions = smd.getTransitions();
        List<String>                     roots       = smd.getRoots();

        if (entities == null || roots == null || roots.isEmpty()) return List.of();

        // ── Step A: build a Set of all descendant IDs for each layer ──────────
        // entityId → layerName (for quick look-up when scanning transitions)
        Map<String, String> entityToLayer = new HashMap<>();
        for (Map.Entry<String, ModelEntityDataFlat> layerEntry : suffixToLayer.entrySet()) {
            String                layerName   = layerEntry.getKey();
            ModelEntityDataFlat   layerEntity = layerEntry.getValue();
            if (layerEntity == null) continue;

            // Collect the full subtree (layer + objects + attributes)
            Set<String> subtreeIds = new HashSet<>();
            collectSubtreeIds(layerEntity, entities, subtreeIds);
            for (String id : subtreeIds) {
                entityToLayer.put(id, layerName);
            }
        }

        // ── Step B: build inter-layer directed edge graph ─────────────────────
        // edgeMap[srcLayer][tgtLayer] = number of cross-layer transitions
        Map<String, Map<String, Integer>> edgeMap = new LinkedHashMap<>();
        for (String layerName : suffixToLayer.keySet()) {
            edgeMap.put(layerName, new LinkedHashMap<>());
        }
        if (transitions != null) {
            for (ModelTransitionData t : transitions.values()) {
                String srcId = t.getSource();
                String tgtId = t.getTarget();
                if (srcId == null || tgtId == null) continue;
                String srcLayer = entityToLayer.get(srcId);
                String tgtLayer = entityToLayer.get(tgtId);
                if (srcLayer != null && tgtLayer != null && !srcLayer.equals(tgtLayer)) {
                    edgeMap.get(srcLayer).merge(tgtLayer, 1, Integer::sum);
                }
            }
        }

        // ── Step C: derive global per-layer totals (for stats / logging only) ─
        Map<String, Integer> totalOut = new LinkedHashMap<>();
        Map<String, Integer> totalIn  = new LinkedHashMap<>();
        for (String layerName : suffixToLayer.keySet()) {
            totalOut.put(layerName, 0);
            totalIn.put(layerName, 0);
        }
        for (Map.Entry<String, Map<String, Integer>> srcEntry : edgeMap.entrySet()) {
            for (Map.Entry<String, Integer> tgtEntry : srcEntry.getValue().entrySet()) {
                totalOut.merge(srcEntry.getKey(),  tgtEntry.getValue(), Integer::sum);
                totalIn.merge(tgtEntry.getKey(), tgtEntry.getValue(), Integer::sum);
            }
        }

        // ── Step D: greedy topological sort → maximises left-to-right flow ────
        List<String> sortedNames = greedyTopologicalSort(edgeMap, suffixToLayer.keySet());

        List<LayerTransitionStats> stats = new ArrayList<>();
        for (String layerName : sortedNames) {
            ModelEntityDataFlat layerEntity = suffixToLayer.get(layerName);
            if (layerEntity == null) continue;
            stats.add(new LayerTransitionStats(
                    layerName,
                    layerEntity.getId(),
                    totalIn.getOrDefault(layerName,  0),
                    totalOut.getOrDefault(layerName, 0)));
        }

        // ── Step E: apply new root order ──────────────────────────────────────
        List<String> newRoots = new ArrayList<>();
        for (LayerTransitionStats s : stats) {
            if (s.entityId != null) newRoots.add(s.entityId);
        }
        // Preserve any roots not captured in suffixToLayer (safety net)
        Set<String> covered = new HashSet<>(newRoots);
        for (String rootId : roots) {
            if (!covered.contains(rootId)) newRoots.add(rootId);
        }
        smd.setRoots(newRoots);

        return stats;
    }

    
    private List<String> greedyTopologicalSort(
            Map<String, Map<String, Integer>> edgeMap,
            Set<String> layers) {

        // ── Pre-pass: compute global in/out weights ────────────────────────
        Map<String, Integer> gOut = new HashMap<>();
        Map<String, Integer> gIn  = new HashMap<>();
        for (String l : layers) { gOut.put(l, 0); gIn.put(l, 0); }
        for (Map.Entry<String, Map<String, Integer>> src : edgeMap.entrySet()) {
            for (Map.Entry<String, Integer> tgt : src.getValue().entrySet()) {
                gOut.merge(src.getKey(),  tgt.getValue(), Integer::sum);
                gIn.merge(tgt.getKey(), tgt.getValue(), Integer::sum);
            }
        }

        // ── Separate FORCED-LAST layers (always rightmost, after everything else) ─
        // These layers are pinned to the far right regardless of their transition
        // statistics: CURATED VIEWS (view-only, read-mostly) and EXTERNAL DATA
        // (objects from foreign DB instances, visually segregated).
        List<String> forcedLast = new ArrayList<>();
        Set<String>  workingSet = new LinkedHashSet<>();
        for (String l : layers) {
            if (FORCED_LAST_LAYERS.contains(l)) {
                forcedLast.add(l);
            } else {
                workingSet.add(l);
            }
        }
        forcedLast.sort(String::compareTo);   // alphabetical among forced-last

        // ── Separate pure sinks from the working set ─────────────────────
        // Pure sink = global out == 0 AND in > 0.
        // Isolated   = global out == 0 AND in == 0  → stays in main sort (natural source).
        List<String> pureSinks  = new ArrayList<>();
        Set<String>  mainLayers = new LinkedHashSet<>();
        for (String l : workingSet) {
            if (gOut.getOrDefault(l, 0) == 0 && gIn.getOrDefault(l, 0) > 0) {
                pureSinks.add(l);
            } else {
                mainLayers.add(l);
            }
        }
        pureSinks.sort(String::compareTo);   // alphabetical for determinism

        // ── Greedy topological sort on non-sink layers ─────────────────────
        // Pre-compute global net for every non-sink layer.
        // isBetterSource uses this to prefer net-positive "producers" over
        // net-negative "consumers that temporarily lost their feeders" when both
        // happen to be topological sources at the same step.
        Map<String, Integer> globalNet = new HashMap<>();
        for (String l : mainLayers) {
            globalNet.put(l, gOut.getOrDefault(l, 0) - gIn.getOrDefault(l, 0));
        }

        Set<String>  remaining = new LinkedHashSet<>(mainLayers);
        List<String> result    = new ArrayList<>();

        while (!remaining.isEmpty()) {
            // Recompute in/out weights restricted to current remaining subgraph
            Map<String, Integer> curOut = new HashMap<>();
            Map<String, Integer> curIn  = new HashMap<>();
            for (String l : remaining) { curOut.put(l, 0); curIn.put(l, 0); }

            for (String src : remaining) {
                for (Map.Entry<String, Integer> e :
                        edgeMap.getOrDefault(src, Map.of()).entrySet()) {
                    String tgt    = e.getKey();
                    int    weight = e.getValue();
                    if (remaining.contains(tgt)) {
                        curOut.merge(src, weight, Integer::sum);
                        curIn.merge(tgt,  weight, Integer::sum);
                    }
                }
            }

            String  best      = null;
            boolean hasSources = false;

            // First pass: find the best topological source (curIn == 0)
            for (String l : remaining) {
                if (curIn.getOrDefault(l, 0) == 0) {
                    hasSources = true;
                    if (isBetterSource(l, best, curOut, globalNet)) best = l;
                }
            }
            // No source → cycle; extract the best cycle-breaker
            if (!hasSources) {
                for (String l : remaining) {
                    if (isBetterCycleBreaker(l, best, curOut, curIn)) best = l;
                }
            }

            result.add(best);
            remaining.remove(best);
        }

        // ── Append pure sinks at the rightmost positions ───────────────────
        result.addAll(pureSinks);

        // ── Append forced-last layers at the absolute rightmost positions ──
        // CURATED VIEWS and EXTERNAL DATA are always placed after everything else.
        result.addAll(forcedLast);
        return result;
    }

    
    private boolean isBetterSource(String l, String best,
                                   Map<String, Integer> curOut,
                                   Map<String, Integer> globalNet) {
        if (best == null) return true;
        // Primary: prefer sources with higher positive global net
        int netL    = Math.max(0, globalNet.getOrDefault(l,    0));
        int netBest = Math.max(0, globalNet.getOrDefault(best, 0));
        if (netL != netBest) return netL > netBest;
        // Secondary: higher current out-weight in remaining subgraph
        int outL    = curOut.getOrDefault(l,    0);
        int outBest = curOut.getOrDefault(best, 0);
        return outL > outBest || (outL == outBest && l.compareTo(best) < 0);
    }

    
    private boolean isBetterCycleBreaker(String l, String best,
                                         Map<String, Integer> curOut,
                                         Map<String, Integer> curIn) {
        if (best == null) return true;
        int netL    = curOut.getOrDefault(l,    0) - curIn.getOrDefault(l,    0);
        int netBest = curOut.getOrDefault(best, 0) - curIn.getOrDefault(best, 0);
        return netL > netBest || (netL == netBest && l.compareTo(best) < 0);
    }

    
    private void collectSubtreeIds(ModelEntityDataFlat entity,
                                   Map<String, ModelEntityDataFlat> entities,
                                   Set<String> result) {
        if (entity == null) return;
        String id = entity.getId();
        if (id != null) {
            if (!result.add(id)) return;   // already visited
        }
        List<String> children = entity.getChildren();
        if (children != null) {
            for (String childId : children) {
                ModelEntityDataFlat child = entities.get(childId);
                if (child != null) {
                    collectSubtreeIds(child, entities, result);
                }
            }
        }
    }

    // =========================================================================
    // Object Analysis Report
    // =========================================================================

    
    private void generateObjectAnalysisReport(
            SolidatusModel displayModel,
            Map<String, ModelEntityDataFlat> suffixToLayer,
            String atomicModelName,
            List<LayerTransitionStats> layerStats,
            String runTimestamp) {

        log.info("========================================");
        log.info("GENERATING OBJECT ANALYSIS REPORT");
        log.info("========================================");

        SimpleModelData smd = displayModel.getSimpleModelData();
        Map<String, ModelEntityDataFlat> entities    = smd.getEntities();
        Map<String, ModelTransitionData> transitions = smd.getTransitions();
        if (entities == null) { log.warn("  No entities – report skipped."); return; }

        // ── Pre-compute transition counts per entity ID ──────────────────────
        Map<String, Integer> entityIn  = new HashMap<>();
        Map<String, Integer> entityOut = new HashMap<>();
        if (transitions != null) {
            for (ModelTransitionData t : transitions.values()) {
                if (t.getSource() != null) entityOut.merge(t.getSource(), 1, Integer::sum);
                if (t.getTarget() != null) entityIn.merge(t.getTarget(),  1, Integer::sum);
            }
        }

        // ── Map every entity ID (layer / object / attribute) back to its
        //    owning Layer name, so cross-layer transitions can be translated
        //    into plain "from layer X" / "to layer Y" relationships. ─────────
        Map<String, String> entityIdToLayer = new HashMap<>();
        for (Map.Entry<String, ModelEntityDataFlat> layerEntry : suffixToLayer.entrySet()) {
            String              layerName   = layerEntry.getKey();
            ModelEntityDataFlat layerEntity = layerEntry.getValue();
            if (layerEntity == null) continue;
            entityIdToLayer.put(layerEntity.getId(), layerName);
            List<String> objectIds = layerEntity.getChildren();
            if (objectIds == null) continue;
            for (String oid : objectIds) {
                entityIdToLayer.put(oid, layerName);
                ModelEntityDataFlat obj = entities.get(oid);
                if (obj == null) continue;
                List<String> attrIds = obj.getChildren();
                if (attrIds == null) continue;
                for (String aid : attrIds) {
                    entityIdToLayer.put(aid, layerName);
                }
            }
        }

        // ── For each layer, the set of OTHER layers it receives data FROM
        //    and sends data TO (cross-layer transitions only; same-layer
        //    links are internal and not reported here). ─────────────────────
        Map<String, Set<String>> layerIncomingFrom = new HashMap<>();
        Map<String, Set<String>> layerOutgoingTo   = new HashMap<>();
        if (transitions != null) {
            for (ModelTransitionData t : transitions.values()) {
                String sourceLayer = entityIdToLayer.get(t.getSource());
                String targetLayer = entityIdToLayer.get(t.getTarget());
                if (sourceLayer == null || targetLayer == null) continue;
                if (sourceLayer.equals(targetLayer)) continue;
                layerOutgoingTo.computeIfAbsent(sourceLayer, k -> new java.util.TreeSet<>()).add(targetLayer);
                layerIncomingFrom.computeIfAbsent(targetLayer, k -> new java.util.TreeSet<>()).add(sourceLayer);
            }
        }

        // ── Ensure output directory exists ───────────────────────────────────
        new File("reports").mkdirs();
        String reportPath = "reports/object-analysis-" + runTimestamp + ".csv";

        // Human-readable timestamp for the header section
        String displayTs = runTimestamp.substring(0, 8).replaceAll("(\\d{4})(\\d{2})(\\d{2})", "$1-$2-$3")
                + " " + runTimestamp.substring(9).replaceAll("(\\d{2})(\\d{2})(\\d{2})", "$1:$2:$3");

        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(reportPath), "UTF-8")))) {

            // =================================================================
            // SECTION A – Run Metadata
            // =================================================================
            pw.println(cv("Report Title",    "Display Model Object Analysis"));
            pw.println(cv("Run Timestamp",   displayTs));
            pw.println(cv("Atomic Model",    atomicModelName));
            pw.println(cv("Atomic Model ID", atomicModelId));
            pw.println(cv("Display Model",   displayModelName));
            pw.println(cv("Solidatus Host",  solidatusHost));
            pw.println(cv("TABLE_CAT Filter (solidatus.api.table-cat)",
                    (tableCat == null || tableCat.isBlank()) ? "(not configured – check disabled)" : tableCat));
            pw.println();

            // =================================================================
            // SECTION B – Layer Summary
            // =================================================================
            pw.println(q("LAYER SUMMARY"));
            pw.println(String.join(",",
                    q("Layer Name"),
                    q("Table Count"),
                    q("Attribute Count"),
                    q("Incoming Relationships"),
                    q("Outgoing Relationships"),
                    q("Net Flow"),
                    q("Receives Data From (Layers)"),
                    q("Sends Data To (Layers)")));

            // Accumulate totals for the grand-total row
            int grandTables = 0, grandAttrs = 0, grandIn = 0, grandOut = 0;

            // Build a quick layer-name → stats lookup from the ordered list
            Map<String, LayerTransitionStats> statsMap = new LinkedHashMap<>();
            for (LayerTransitionStats ls : layerStats) statsMap.put(ls.layerName, ls);

            for (Map.Entry<String, ModelEntityDataFlat> layerEntry : suffixToLayer.entrySet()) {
                String              layerName   = layerEntry.getKey();
                ModelEntityDataFlat layerEntity = layerEntry.getValue();
                if (layerEntity == null) continue;

                int tableCnt = 0, attrCnt = 0, layerIn = 0, layerOut = 0;
                List<String> objectIds = layerEntity.getChildren();
                if (objectIds != null) {
                    for (String oid : objectIds) {
                        ModelEntityDataFlat obj = entities.get(oid);
                        if (obj == null) continue;
                        tableCnt++;
                        List<String> attrIds = obj.getChildren();
                        int objAttr = (attrIds == null) ? 0 : attrIds.size();
                        attrCnt += objAttr;

                        // Attribute-level + object-level relationship counts
                        layerIn  += entityIn.getOrDefault(oid, 0);
                        layerOut += entityOut.getOrDefault(oid, 0);
                        if (attrIds != null) {
                            for (String aid : attrIds) {
                                layerIn  += entityIn.getOrDefault(aid, 0);
                                layerOut += entityOut.getOrDefault(aid, 0);
                            }
                        }
                    }
                }

                grandTables += tableCnt;
                grandAttrs  += attrCnt;
                grandIn     += layerIn;
                grandOut    += layerOut;

                int net = layerOut - layerIn;
                pw.println(String.join(",",
                        q(layerName),
                        q(tableCnt),
                        q(attrCnt),
                        q(layerIn),
                        q(layerOut),
                        q(net > 0 ? "+" + net : String.valueOf(net)),
                        q(joinLayerNames(layerIncomingFrom.get(layerName))),
                        q(joinLayerNames(layerOutgoingTo.get(layerName)))));
            }
            // Grand total row
            int grandNet = grandOut - grandIn;
            pw.println(String.join(",",
                    q("TOTAL"),
                    q(grandTables), q(grandAttrs), q(grandIn), q(grandOut),
                    q(grandNet > 0 ? "+" + grandNet : String.valueOf(grandNet)),
                    q(""), q("")));
            pw.println();

            // =================================================================
            // SECTION C – Object Detail (one row per table)
            // =================================================================
            pw.println(q("OBJECT DETAIL"));
            pw.println(String.join(",",
                    q("Layer"),
                    q("Table Name"),
                    q("TABLE_CAT"),
                    q("Schema"),
                    q("Prefix - 1 Segment"),
                    q("Prefix - 2 Segments"),
                    q("Suffix - 1 Segment"),
                    q("Suffix - 2 Segments"),
                    q("Attribute Count"),
                    q("Incoming Relationships"),
                    q("Outgoing Relationships"),
                    q("Is Backup Table")));

            int rowCount = 0;
            for (Map.Entry<String, ModelEntityDataFlat> layerEntry : suffixToLayer.entrySet()) {
                String              layerName   = layerEntry.getKey();
                ModelEntityDataFlat layerEntity = layerEntry.getValue();
                if (layerEntity == null) continue;

                List<String> objectIds = layerEntity.getChildren();
                if (objectIds == null) continue;

                for (String oid : objectIds) {
                    ModelEntityDataFlat obj = entities.get(oid);
                    if (obj == null) continue;

                    String objectName = safeGetName(obj);
                    if (objectName.isEmpty()) continue;

                    Map<String, String> props = obj.getProperties();

                    // ── Property resolution ──────────────────────────────────
                    String tableCat = prop(props, "TABLE_CAT", "table_cat", "Database", "DB", "database");
                    String schema   = prop(props, "TABLE_SCHEMA", "table_schema", "Schema", "SCHEMA", "schema");

                    // ── Naming analysis ──────────────────────────────────────
                    String p1 = namePrefix(objectName, 1);
                    String p2 = namePrefix(objectName, 2);
                    String s1 = nameSuffix(objectName, 1);
                    String s2 = nameSuffix(objectName, 2);

                    // ── Relationship counts (object + its attributes) ────────
                    List<String> attrIds = obj.getChildren();
                    int attrCount = (attrIds == null) ? 0 : attrIds.size();
                    int inRels  = entityIn.getOrDefault(oid, 0);
                    int outRels = entityOut.getOrDefault(oid, 0);
                    if (attrIds != null) {
                        for (String aid : attrIds) {
                            inRels  += entityIn.getOrDefault(aid, 0);
                            outRels += entityOut.getOrDefault(aid, 0);
                        }
                    }

                    // ── Backup flag ──────────────────────────────────────────
                    boolean isBackup = isBackupTable(objectName.toUpperCase().trim());

                    pw.println(String.join(",",
                            q(layerName),
                            q(objectName),
                            q(tableCat),
                            q(schema),
                            q(p1), q(p2), q(s1), q(s2),
                            q(attrCount),
                            q(inRels),
                            q(outRels),
                            q(isBackup ? "Yes" : "No")));
                    rowCount++;
                }
            }

            log.info("  Report written : {} ({} tables)", reportPath, rowCount);
            log.info("  Sections       : Run Metadata | Layer Summary ({} layers) | Object Detail",
                     suffixToLayer.size());

        } catch (Exception e) {
            log.error("  ✗ Failed to write object analysis report: {}", e.getMessage(), e);
        }
    }

    // ── Report helper methods ─────────────────────────────────────────────────

    
    private String cv(String key, String value) {
        return q(key) + "," + q(value);
    }

    
    private String q(Object value) {
        if (value == null) return "\"\"";
        String s = value.toString();
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    
    private String namePrefix(String name, int parts) {
        if (name == null || name.isEmpty()) return "";
        int count = 0;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '_') {
                if (++count == parts) return name.substring(0, i);
            }
        }
        return name;   // fewer delimiters than requested
    }

    
    private String nameSuffix(String name, int parts) {
        if (name == null || name.isEmpty()) return "";
        int count = 0;
        for (int i = name.length() - 1; i >= 0; i--) {
            if (name.charAt(i) == '_') {
                if (++count == parts) return name.substring(i + 1);
            }
        }
        return name;   // fewer delimiters than requested
    }

    
    private String prop(Map<String, String> props, String... keys) {
        if (props == null) return "";
        for (String key : keys) {
            String v = props.get(key);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    
    private String joinLayerNames(Set<String> layerNames) {
        if (layerNames == null || layerNames.isEmpty()) return "(none)";
        return String.join(", ", layerNames);
    }

    
    private ModelQueryData buildObjectLinkDisplayRule() {

        // ── querySource ──────────────────────────────────────────────────────
        // Confirmed working in the Solidatus UI (shows "11 matches").
        // isObject() is the Solidatus built-in predicate that selects every
        // Object-type entity (table level) across the model.
        String querySource = "isObject()";

        // ── displayRules ─────────────────────────────────────────────────────
        // Single JSON object – NO array wrapper.
        // Solidatus v2 expects a plain object here, not an array.
        //
        //  type   : "link"         → activates the LINK tab in the display-rule editor
        //  href                    → where to read the URL from:
        //    type : "property"     → URL is sourced from an entity property value
        //    key  : <prop name>    → name of the entity property holding the model URL
        //  icon   : "link"         → Material Design chain-link icon shown on the entity
        //  tooltip: hover text     → shown on mouse-over in the Solidatus canvas UI
        String displayRules = "{"
                + "\"type\":\"link\","
                + "\"href\":{"
                +     "\"type\":\"property\","
                +     "\"key\":\"" + SOURCE_MODEL_LINK + "\""
                + "},"
                + "\"icon\":\"link\","
                + "\"tooltip\":\"Open source Atomic Model in Solidatus\""
                + "}";

        return new ModelQueryData()
                .name("Atomic Model Link")
                .description(
                        "Displays a clickable link icon on every Object (table) entity. "
                        + "Clicking the icon navigates to the source Atomic Model in Solidatus. "
                        + "Applies to all objects whose '" + SOURCE_MODEL_LINK + "' property is set.")
                .querySource(querySource)
                .displayRules(displayRules);
    }
}
