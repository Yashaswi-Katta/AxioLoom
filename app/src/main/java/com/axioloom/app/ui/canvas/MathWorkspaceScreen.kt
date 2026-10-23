package com.axioloom.app.ui.canvas

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axioloom.app.domain.cas.DerivationStep
import com.axioloom.app.domain.cas.TransferFunctionAnalysis
import com.axioloom.app.domain.cas.TransferFunctionAnalyzer
import com.axioloom.app.domain.codegen.CPrecision
import com.axioloom.app.domain.codegen.EmbeddedCGenerator
import com.axioloom.app.domain.dag.FastNumericResult
import com.axioloom.app.domain.export.MarkdownExporter
import com.axioloom.app.domain.export.PdfTheme
import com.axioloom.app.domain.clustering.InkWorkspaceState
import com.axioloom.app.domain.dag.ReactiveGraphSnapshot
import com.axioloom.app.domain.model.BlockDisplayMode
import com.axioloom.app.domain.model.BlockStatus
import com.axioloom.app.domain.model.CasMode
import com.axioloom.app.domain.model.EquationBlock
import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.model.MathIntent
import com.axioloom.app.domain.model.SymbolicTransform
import com.axioloom.app.ml.hmer.ExternalModelStatus
import com.axioloom.app.ui.AxioLoomViewModel
import com.axioloom.app.ui.theme.AxioLoomLogoIcon
import com.axioloom.app.ui.theme.BodeIcon
import com.axioloom.app.ui.theme.CircuitIcon
import com.axioloom.app.ui.theme.CodeIcon
import com.axioloom.app.ui.theme.ConstantsIcon
import com.axioloom.app.ui.theme.ControlIcon
import com.axioloom.app.ui.theme.Curve1DIcon
import com.axioloom.app.ui.theme.DspStemIcon
import com.axioloom.app.ui.theme.EraserIcon
import com.axioloom.app.ui.theme.InspectorIcon
import com.axioloom.app.ui.theme.KMapIcon
import com.axioloom.app.ui.theme.OdeIcon
import com.axioloom.app.ui.theme.PlotIcon
import com.axioloom.app.ui.theme.RfSmithIcon
import com.axioloom.app.ui.theme.RootLocusIcon
import com.axioloom.app.ui.theme.SchematicIcon
import com.axioloom.app.ui.theme.StepsIcon
import com.axioloom.app.ui.theme.Surface3DIcon
import com.axioloom.app.ui.theme.TemplatesIcon
import com.axioloom.app.ui.theme.UmbraColors
import com.axioloom.app.ui.theme.VectorFieldIcon
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class WorkbenchTab(val label: String) {
    INSPECTOR("Inspector"),
    STEPS("Steps"),
    PLOT("Plot 2D/3D"),
    CONTROL("Control (Bode/RL)"),
    RF_SMITH("RF Smith"),
    CIRCUIT("Circuit MNA"),
    KMAP("Logic K-Map"),
    CODE("C99 Code")
}

enum class PlotSubTab(val label: String) {
    CURVE_1D("1D Curve"),
    SURFACE_3D("3D Surface"),
    VECTOR("Vector Field"),
    DSP("Discrete DSP"),
    ODE("RK45 ODE")
}

enum class ControlSubTab(val label: String) {
    BODE("Bode & S-Plane"),
    ROOT_LOCUS("Root-Locus & Nyquist")
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MathWorkspaceScreen(viewModel: AxioLoomViewModel) {
    val context = LocalContext.current
    val workspace by viewModel.inkWorkspace.collectAsState()
    val graph by viewModel.workspace.collectAsState()
    val recognitionMessages by viewModel.recognitionMessages.collectAsState()
    val fastNumericOverrides by viewModel.fastNumericOverrides.collectAsState()
    val warmupError by viewModel.warmupError.collectAsState()
    val inputError by viewModel.inputError.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val toolMode by viewModel.toolMode.collectAsState()
    val physicalConstantsEnabled by viewModel.physicalConstantsEnabled.collectAsState()
    val casMode by viewModel.casMode.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val activeSchematic by viewModel.activeSchematic.collectAsState()
    val mnaResult by viewModel.mnaResult.collectAsState()
    val dimensionalWarnings by viewModel.dimensionalWarnings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val viewport = remember { ViewportState() }
    var selectedBlockId by remember { mutableStateOf<UUID?>(null) }
    var manualBlock by remember { mutableStateOf<EquationBlock?>(null) }

    var activeWorkbenchTab by remember { mutableStateOf(WorkbenchTab.INSPECTOR) }
    var plotSubTab by remember { mutableStateOf(PlotSubTab.CURVE_1D) }
    var controlSubTab by remember { mutableStateOf(ControlSubTab.BODE) }
    var isRibbonExpanded by remember { mutableStateOf(false) }

    var showTelemetryHud by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showCodeExportDialog by remember { mutableStateOf(false) }
    var codeExportBlock by remember { mutableStateOf<EquationBlock?>(null) }

    var showRadialMenu by remember { mutableStateOf(false) }
    var radialScreenPos by remember { mutableStateOf(Offset.Zero) }
    var radialWorldPos by remember { mutableStateOf(Offset.Zero) }

    var showNotebookMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showNewPageDialog by remember { mutableStateOf(false) }
    var newPageTitle by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTitle by remember { mutableStateOf("") }

    var derivationSteps by remember { mutableStateOf<List<DerivationStep>>(emptyList()) }
    var isDerivationLoading by remember { mutableStateOf(false) }
    var showModelStatusDialog by remember { mutableStateOf(false) }
    var showCalibrationDialog by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        viewModel.importImageBitmap(bmp)
                        Toast.makeText(context, "Preprocessing & recognizing image math...", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Image import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val boundValues = remember(graph) {
        graph.symbolOwners.mapNotNull { (key, owners) ->
            if (owners.size == 1) {
                val expr = graph.blocks[owners.single()]?.result?.expression
                expr?.toDoubleOrNull()?.let { key.name to it }
            } else null
        }.toMap()
    }

    val selectedBlock = workspace.blocks.firstOrNull { it.id == selectedBlockId }
    val activeFunctionDef = (selectedBlock?.intent as? MathIntent.FunctionDef)
        ?: (workspace.blocks.mapNotNull { it.intent as? MathIntent.FunctionDef }.firstOrNull())
    val activeTransferFunction = remember(selectedBlock?.intent, workspace.blocks) {
        selectedBlock?.intent?.let { TransferFunctionAnalyzer.analyze(it) }
            ?: workspace.blocks.firstNotNullOfOrNull { it.intent?.let { intent -> TransferFunctionAnalyzer.analyze(intent) } }
    }

    val active3DExpr = remember(selectedBlock?.intent, workspace.blocks) {
        (selectedBlock?.intent as? MathIntent.FunctionDef)?.body
            ?: workspace.blocks.firstNotNullOfOrNull { (it.intent as? MathIntent.FunctionDef)?.body }
            ?: MathExpr.Binary(
                MathExpr.Op.SUBTRACT,
                MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Symbol("x"), MathExpr.Number("2")),
                MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Symbol("y"), MathExpr.Number("2"))
            )
    }

    val activePExpr = remember {
        MathExpr.Binary(MathExpr.Op.SUBTRACT, MathExpr.Symbol("y"), MathExpr.Number("0"))
    }
    val activeQExpr = remember {
        MathExpr.Binary(MathExpr.Op.SUBTRACT, MathExpr.Number("0"), MathExpr.Symbol("x"))
    }

    // Automatically load derivation steps when STEPS tab is active or selected block changes
    LaunchedEffect(activeWorkbenchTab, selectedBlockId) {
        if (activeWorkbenchTab == WorkbenchTab.STEPS) {
            val targetId = selectedBlockId ?: workspace.blocks.firstOrNull { it.intent != null }?.id
            if (targetId != null) {
                isDerivationLoading = true
                derivationSteps = viewModel.getDerivationSteps(targetId)
                isDerivationLoading = false
            } else {
                derivationSteps = emptyList()
            }
        }
    }

    fun openManualAt(world: Offset) {
        val tolerance = 32f / viewport.scale
        val hit = workspace.blocks.firstOrNull { block ->
            world.x >= block.bounds.left - tolerance && world.x <= block.bounds.right + tolerance &&
                world.y >= block.bounds.top - tolerance && world.y <= block.bounds.bottom + tolerance
        }
        if (hit != null) {
            selectedBlockId = hit.id
            manualBlock = hit
        }
    }

    Column(Modifier.fillMaxSize().background(UmbraColors.BgCanvas)) {
        // -----------------------------------------------------------------
        // 1. Umbra Top Application Header (Section 1)
        // -----------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(UmbraColors.BgSurface)
                .border(width = 1.dp, color = UmbraColors.BorderHairline)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Identity: Logo + Vertical Divider + Notebook Taxonomy
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AxioLoomLogoIcon(size = 22.dp, tint = UmbraColors.AccentAmber)

                // 1px Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(UmbraColors.BorderSubtle)
                )

                Box {
                    Column(
                        modifier = Modifier
                            .clickable { showNotebookMenu = true }
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = currentPage?.title ?: "Main Notebook",
                                color = UmbraColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "▼",
                                color = UmbraColors.TextMuted,
                                fontSize = 9.sp
                            )
                        }
                        Text(
                            text = "PROJECT / NOTEBOOK",
                            color = UmbraColors.TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            letterSpacing = 0.18.sp
                        )
                    }

                    DropdownMenu(
                        expanded = showNotebookMenu,
                        onDismissRequest = { showNotebookMenu = false },
                        modifier = Modifier.background(UmbraColors.BgElevated)
                    ) {
                        pages.forEach { page ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = page.title,
                                        fontWeight = if (page.id == currentPage?.id) FontWeight.Bold else FontWeight.Normal,
                                        color = if (page.id == currentPage?.id) UmbraColors.AccentAmber else UmbraColors.TextPrimary
                                    )
                                },
                                onClick = {
                                    viewModel.switchPage(page.id)
                                    selectedBlockId = null
                                    showNotebookMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("+ New Page", color = UmbraColors.AccentAmber) },
                            onClick = {
                                showNotebookMenu = false
                                newPageTitle = "Page ${pages.size + 1}"
                                showNewPageDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Starter Templates...", color = UmbraColors.AccentAmberDim) },
                            onClick = {
                                showNotebookMenu = false
                                showTemplateDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename Current", color = UmbraColors.TextSecondary) },
                            onClick = {
                                showNotebookMenu = false
                                renameTitle = currentPage?.title ?: ""
                                showRenameDialog = true
                            }
                        )
                        if (pages.size > 1) {
                            DropdownMenuItem(
                                text = { Text("Delete Page", color = UmbraColors.AccentDanger) },
                                onClick = {
                                    showNotebookMenu = false
                                    viewModel.deleteCurrentPage()
                                }
                            )
                        }
                    }
                }
            }

            // Right Controls: Mode Pill, CAS Toggle, Undo, Redo, Overflow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Prototype Mode Pill (Umbra Section 1)
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = UmbraColors.BgActiveAmber,
                    border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderAmber),
                    modifier = Modifier.height(26.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(UmbraColors.AccentAmber, shape = CircleShape)
                        )
                        Text(
                            text = "Prototype Mode",
                            color = UmbraColors.AccentAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // CAS Mode Toggle Pill (Symbolic vs Numeric)
                Surface(
                    onClick = { viewModel.toggleCasMode() },
                    shape = RoundedCornerShape(3.dp),
                    color = UmbraColors.BgSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
                    modifier = Modifier.height(26.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (casMode == CasMode.NUMERIC) "Num" else "Sym",
                            color = if (casMode == CasMode.NUMERIC) UmbraColors.AccentAmber else UmbraColors.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Undo
                Surface(
                    onClick = viewModel::undo,
                    enabled = canUndo,
                    shape = RoundedCornerShape(3.dp),
                    color = UmbraColors.BgSurface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (canUndo) UmbraColors.BorderFocus else UmbraColors.BorderHairline
                    ),
                    modifier = Modifier.height(26.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "← Undo",
                            color = if (canUndo) UmbraColors.TextPrimary else UmbraColors.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                // Redo
                Surface(
                    onClick = viewModel::redo,
                    enabled = canRedo,
                    shape = RoundedCornerShape(3.dp),
                    color = UmbraColors.BgSurface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (canRedo) UmbraColors.BorderFocus else UmbraColors.BorderHairline
                    ),
                    modifier = Modifier.height(26.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Redo →",
                            color = if (canRedo) UmbraColors.TextPrimary else UmbraColors.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                // 3-dot Overflow Menu
                Box {
                    Surface(
                        onClick = { showOverflowMenu = true },
                        shape = RoundedCornerShape(3.dp),
                        color = UmbraColors.BgSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
                        modifier = Modifier.size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("⋮", color = UmbraColors.TextPrimary, fontSize = 14.sp)
                        }
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                        modifier = Modifier.background(UmbraColors.BgElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import Image (Sauvola OCR)", color = UmbraColors.AccentAmber) },
                            onClick = {
                                showOverflowMenu = false
                                imagePickerLauncher.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Calibrate Handwriting (DTW)", color = UmbraColors.AccentAmberDim) },
                            onClick = {
                                showOverflowMenu = false
                                showCalibrationDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("HMER Model Status / Import", color = UmbraColors.AccentAmberDim) },
                            onClick = {
                                showOverflowMenu = false
                                showModelStatusDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Obsidian Markdown (.md)", color = UmbraColors.TextPrimary) },
                            onClick = {
                                showOverflowMenu = false
                                val md = viewModel.exportMarkdown()
                                MarkdownExporter.copyToClipboard(context, "AxioLoom Notes", md)
                                Toast.makeText(context, "Copied Obsidian Markdown", Toast.LENGTH_SHORT).show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Vector PDF (.pdf)", color = UmbraColors.TextPrimary) },
                            onClick = {
                                showOverflowMenu = false
                                try {
                                    val pdfFile = File(context.cacheDir, "${currentPage?.title ?: "notebook"}.pdf")
                                    FileOutputStream(pdfFile).use { out ->
                                        viewModel.exportPdf(out, PdfTheme.DARK_ENGINEERING)
                                    }
                                    Toast.makeText(context, "Saved PDF to ${pdfFile.name}", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "PDF export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (showTelemetryHud) "Hide HUD" else "Show Telemetry HUD", color = UmbraColors.TextSecondary) },
                            onClick = {
                                showOverflowMenu = false
                                showTelemetryHud = !showTelemetryHud
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reset Viewport Zoom", color = UmbraColors.TextSecondary) },
                            onClick = {
                                showOverflowMenu = false
                                viewport.reset()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Canvas", color = UmbraColors.AccentDanger) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.clearWorkspace()
                                selectedBlockId = null
                            }
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 2. Umbra Architectural Tool Strip (Section 2)
        // -----------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(UmbraColors.BgCanvas)
                .border(width = 1.dp, color = UmbraColors.BorderHairline)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            UmbraToolTab(
                label = "Draw",
                isActive = toolMode == CanvasToolMode.DRAW,
                onClick = { viewModel.setToolMode(CanvasToolMode.DRAW) }
            )
            UmbraToolTab(
                label = "Select",
                isActive = toolMode == CanvasToolMode.PAN_SELECT,
                onClick = { viewModel.setToolMode(CanvasToolMode.PAN_SELECT) }
            )
            UmbraToolTab(
                label = "Eraser",
                isActive = toolMode == CanvasToolMode.ERASER,
                onClick = { viewModel.setToolMode(CanvasToolMode.ERASER) },
                icon = { tint -> EraserIcon(tint = tint) }
            )
            UmbraToolTab(
                label = "Schematic",
                isActive = toolMode == CanvasToolMode.SCHEMATIC,
                onClick = {
                    viewModel.setToolMode(CanvasToolMode.SCHEMATIC)
                    activeWorkbenchTab = WorkbenchTab.CIRCUIT
                    isRibbonExpanded = true
                },
                icon = { tint -> SchematicIcon(tint = tint) }
            )
            UmbraToolTab(
                label = "SI Constants",
                isActive = physicalConstantsEnabled,
                onClick = viewModel::togglePhysicalConstants,
                icon = { tint -> ConstantsIcon(tint = tint) }
            )
            UmbraToolTab(
                label = "Templates",
                isActive = showTemplateDialog,
                onClick = { showTemplateDialog = true },
                icon = { tint -> TemplatesIcon(tint = tint) }
            )
            if (toolMode == CanvasToolMode.SCHEMATIC) {
                Surface(
                    onClick = { viewModel.solveCurrentSchematic() },
                    color = UmbraColors.BgActiveAmber,
                    shape = RoundedCornerShape(3.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderAmber),
                    modifier = Modifier.height(28.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Solve Schematic",
                            color = UmbraColors.AccentAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 3. Adaptive Workspace: Canvas + Pull-up Bottom Ribbon
        // -----------------------------------------------------------------
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val isTablet = maxWidth >= 720.dp

            if (isTablet) {
                // Tablet Landscape: 65% Interactive Canvas, 35% Docked Workbench Sidebar
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.65f).fillMaxSize()) {
                        DryInkCanvas(
                            workspace = workspace,
                            graph = graph,
                            viewport = viewport,
                            selectedBlockId = selectedBlockId,
                            fastNumericOverrides = fastNumericOverrides,
                            activeSchematic = activeSchematic,
                            mnaResult = mnaResult,
                            dimensionalWarnings = dimensionalWarnings
                        )
                        WetInkOverlay(
                            viewport = viewport,
                            toolMode = toolMode,
                            blocks = workspace.blocks,
                            selectedBlockId = selectedBlockId,
                            onSelectBlock = { selectedBlockId = it },
                            onMoveBlock = viewModel::moveBlock,
                            onDeleteBlock = viewModel::removeBlock,
                            onEraseAt = viewModel::eraseAt,
                            onStrokeFinished = viewModel::commitFinishedStroke,
                            onTimedStrokeFinished = viewModel::commitFinishedTimedStroke,
                            onLongPressWorld = ::openManualAt,
                            onDoubleTapWorld = { screenPos, worldPos ->
                                radialScreenPos = screenPos
                                radialWorldPos = worldPos
                                showRadialMenu = true
                            }
                        )
                        if (showRadialMenu) {
                            RadialSymbolMenu(
                                screenCenter = radialScreenPos,
                                worldCenter = radialWorldPos,
                                onSelectTemplate = { template, worldPos ->
                                    viewModel.insertEquationTemplate(template, worldPos)
                                    showRadialMenu = false
                                },
                                onDismiss = { showRadialMenu = false }
                            )
                        }
                        if (showTelemetryHud) {
                            PerformanceOverlayBadge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp),
                                telemetry = telemetry
                            )
                        }
                    }

                    // Docked 35% Workbench Sidebar
                    Surface(
                        modifier = Modifier.weight(0.35f).fillMaxHeight(),
                        color = UmbraColors.BgSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderHairline)
                    ) {
                        EngineeringWorkbenchPanel(
                            workspace = workspace,
                            graph = graph,
                            recognitionMessages = recognitionMessages,
                            selectedBlockId = selectedBlockId,
                            fastNumericOverrides = fastNumericOverrides,
                            activeTransferFunction = activeTransferFunction,
                            active3DExpr = active3DExpr,
                            activePExpr = activePExpr,
                            activeQExpr = activeQExpr,
                            boundValues = boundValues,
                            activeFunctionDef = activeFunctionDef,
                            dimensionalWarnings = dimensionalWarnings,
                            derivationSteps = derivationSteps,
                            isDerivationLoading = isDerivationLoading,
                            activeTab = activeWorkbenchTab,
                            onTabSelected = { activeWorkbenchTab = it },
                            plotSubTab = plotSubTab,
                            onPlotSubTabSelected = { plotSubTab = it },
                            controlSubTab = controlSubTab,
                            onControlSubTabSelected = { controlSubTab = it },
                            onSelectBlock = {
                                selectedBlockId = it.id
                                if (it.intent?.let { inT -> TransferFunctionAnalyzer.analyze(inT) } != null) {
                                    activeWorkbenchTab = WorkbenchTab.CONTROL
                                } else if (it.intent is MathIntent.FunctionDef) {
                                    activeWorkbenchTab = WorkbenchTab.PLOT
                                }
                            },
                            onManualBlock = {
                                selectedBlockId = it.id
                                manualBlock = it
                            },
                            onToggleDisplayMode = viewModel::toggleBlockDisplayMode,
                            onSetTransform = viewModel::setBlockTransform,
                            onSetSolveTarget = viewModel::setBlockSolveTarget,
                            onGroupAsSystem = viewModel::groupBlocksAsSystem,
                            onParameterScrub = viewModel::onParameterSliderScrub,
                            onParameterRelease = viewModel::onParameterSliderRelease,
                            onSelectAlternative = viewModel::selectBlockAlternative,
                            onGenerateCode = { block ->
                                codeExportBlock = block
                                showCodeExportDialog = true
                            },
                            onSetToolMode = viewModel::setToolMode,
                            onSolveSchematic = viewModel::solveCurrentSchematic,
                            mnaSolver = viewModel.mnaSolver
                        )
                    }
                }
            } else {
                // Mobile Portrait (< 720dp): Technical Canvas with Pull-Up Bottom Workbench Ribbon
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        DryInkCanvas(
                            workspace = workspace,
                            graph = graph,
                            viewport = viewport,
                            selectedBlockId = selectedBlockId,
                            fastNumericOverrides = fastNumericOverrides,
                            activeSchematic = activeSchematic,
                            mnaResult = mnaResult,
                            dimensionalWarnings = dimensionalWarnings
                        )
                        WetInkOverlay(
                            viewport = viewport,
                            toolMode = toolMode,
                            blocks = workspace.blocks,
                            selectedBlockId = selectedBlockId,
                            onSelectBlock = { selectedBlockId = it },
                            onMoveBlock = viewModel::moveBlock,
                            onDeleteBlock = viewModel::removeBlock,
                            onEraseAt = viewModel::eraseAt,
                            onStrokeFinished = viewModel::commitFinishedStroke,
                            onTimedStrokeFinished = viewModel::commitFinishedTimedStroke,
                            onLongPressWorld = ::openManualAt,
                            onDoubleTapWorld = { screenPos, worldPos ->
                                radialScreenPos = screenPos
                                radialWorldPos = worldPos
                                showRadialMenu = true
                            }
                        )
                        if (showRadialMenu) {
                            RadialSymbolMenu(
                                screenCenter = radialScreenPos,
                                worldCenter = radialWorldPos,
                                onSelectTemplate = { template, worldPos ->
                                    viewModel.insertEquationTemplate(template, worldPos)
                                    showRadialMenu = false
                                },
                                onDismiss = { showRadialMenu = false }
                            )
                        }
                        if (showTelemetryHud) {
                            PerformanceOverlayBadge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp),
                                telemetry = telemetry
                            )
                        }
                    }

                    // -------------------------------------------------------------
                    // Pull-up Bottom Ribbon Container
                    // -------------------------------------------------------------
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = UmbraColors.BgSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderHairline)
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            // Ribbon Drag / Toggle Header Strip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .clickable { isRibbonExpanded = !isRibbonExpanded }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(UmbraColors.AccentGreen, shape = CircleShape)
                                    )
                                    Text(
                                        text = "WORKBENCH  /  INSTRUMENTATION",
                                        color = UmbraColors.TextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.16.sp
                                    )
                                }

                                Surface(
                                    onClick = { isRibbonExpanded = !isRibbonExpanded },
                                    color = if (isRibbonExpanded) UmbraColors.BgSurface else UmbraColors.BgActiveAmber,
                                    shape = RoundedCornerShape(3.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isRibbonExpanded) UmbraColors.BorderSubtle else UmbraColors.BorderAmber
                                    ),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = if (isRibbonExpanded) "▼ COLLAPSE" else "▲ EXPAND WORKBENCH",
                                            color = if (isRibbonExpanded) UmbraColors.TextSecondary else UmbraColors.AccentAmber,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            // Quick horizontal option strip when collapsed
                            if (!isRibbonExpanded) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    WorkbenchTab.values().forEach { tab ->
                                        UmbraWorkbenchTile(
                                            label = tab.label,
                                            isActive = activeWorkbenchTab == tab,
                                            onClick = {
                                                activeWorkbenchTab = tab
                                                isRibbonExpanded = true
                                            },
                                            icon = { tint ->
                                                when (tab) {
                                                    WorkbenchTab.INSPECTOR -> InspectorIcon(tint = tint)
                                                    WorkbenchTab.STEPS -> StepsIcon(tint = tint)
                                                    WorkbenchTab.PLOT -> PlotIcon(tint = tint)
                                                    WorkbenchTab.CONTROL -> ControlIcon(tint = tint)
                                                    WorkbenchTab.CIRCUIT -> CircuitIcon(tint = tint)
                                                    WorkbenchTab.RF_SMITH -> RfSmithIcon(tint = tint)
                                                    WorkbenchTab.KMAP -> KMapIcon(tint = tint)
                                                    WorkbenchTab.CODE -> CodeIcon(tint = tint)
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Full Expanded Workbench Panel
                            AnimatedVisibility(
                                visible = isRibbonExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 300.dp, max = 440.dp)
                                ) {
                                    EngineeringWorkbenchPanel(
                                        workspace = workspace,
                                        graph = graph,
                                        recognitionMessages = recognitionMessages,
                                        selectedBlockId = selectedBlockId,
                                        fastNumericOverrides = fastNumericOverrides,
                                        activeTransferFunction = activeTransferFunction,
                                        active3DExpr = active3DExpr,
                                        activePExpr = activePExpr,
                                        activeQExpr = activeQExpr,
                                        boundValues = boundValues,
                                        activeFunctionDef = activeFunctionDef,
                                        dimensionalWarnings = dimensionalWarnings,
                                        derivationSteps = derivationSteps,
                                        isDerivationLoading = isDerivationLoading,
                                        activeTab = activeWorkbenchTab,
                                        onTabSelected = { activeWorkbenchTab = it },
                                        plotSubTab = plotSubTab,
                                        onPlotSubTabSelected = { plotSubTab = it },
                                        controlSubTab = controlSubTab,
                                        onControlSubTabSelected = { controlSubTab = it },
                                        onSelectBlock = {
                                            selectedBlockId = it.id
                                            if (it.intent?.let { inT -> TransferFunctionAnalyzer.analyze(inT) } != null) {
                                                activeWorkbenchTab = WorkbenchTab.CONTROL
                                            } else if (it.intent is MathIntent.FunctionDef) {
                                                activeWorkbenchTab = WorkbenchTab.PLOT
                                            }
                                        },
                                        onManualBlock = {
                                            selectedBlockId = it.id
                                            manualBlock = it
                                        },
                                        onToggleDisplayMode = viewModel::toggleBlockDisplayMode,
                                        onSetTransform = viewModel::setBlockTransform,
                                        onSetSolveTarget = viewModel::setBlockSolveTarget,
                                        onGroupAsSystem = viewModel::groupBlocksAsSystem,
                                        onParameterScrub = viewModel::onParameterSliderScrub,
                                        onParameterRelease = viewModel::onParameterSliderRelease,
                                        onSelectAlternative = viewModel::selectBlockAlternative,
                                        onGenerateCode = { block ->
                                            codeExportBlock = block
                                            showCodeExportDialog = true
                                        },
                                        onSetToolMode = viewModel::setToolMode,
                                        onSolveSchematic = viewModel::solveCurrentSchematic,
                                        mnaSolver = viewModel.mnaSolver
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 4. Umbra Diagnostic & System Watermark Footer Bar (Section 6)
        // -----------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(UmbraColors.BgCanvas)
                .border(width = 1.dp, color = UmbraColors.BorderHairline)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Nominal Live Telemetry or Fault Message
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!warmupError.isNullOrBlank() || !inputError.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(UmbraColors.AccentDanger, shape = CircleShape)
                    )
                    Text(
                        text = listOfNotNull(warmupError, inputError).joinToString(" · "),
                        color = UmbraColors.AccentDanger,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(UmbraColors.AccentGreen, shape = CircleShape)
                    )
                    Text(
                        text = "ENGINEERING CORE READY",
                        color = UmbraColors.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 0.14.sp
                    )
                }
            }

            // Right: System Watermark
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "AXIOLOOM ──",
                    color = UmbraColors.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.22.sp
                )
            }
        }
    }

    if (showModelStatusDialog) {
        val modelStatus = remember { viewModel.externalModelManager.getStatus() }
        ModelStatusDialog(
            status = modelStatus,
            onDismiss = { showModelStatusDialog = false },
            onClearCustom = { viewModel.externalModelManager.clearCustomModel() }
        )
    }

    if (showNewPageDialog) {
        AlertDialog(
            onDismissRequest = { showNewPageDialog = false },
            title = { Text("Create New Page") },
            text = {
                OutlinedTextField(
                    value = newPageTitle,
                    onValueChange = { newPageTitle = it },
                    label = { Text("Page Title") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createNewPage(newPageTitle)
                    showNewPageDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPageDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Page") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    label = { Text("Page Title") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameCurrentPage(renameTitle)
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    manualBlock?.let { block ->
        ManualLatexDialog(
            block = block,
            onDismiss = { manualBlock = null },
            onSubmit = { manualLatex ->
                val accepted = viewModel.injectManualLatex(block.ticket, manualLatex)
                if (accepted) manualBlock = null
                accepted
            }
        )
    }

    if (showCalibrationDialog) {
        HandwritingCalibrationDialog(
            onDismiss = { showCalibrationDialog = false },
            onSavePrototype = { label, latex, strokes ->
                viewModel.addCustomPrototype(label, latex, strokes)
                Toast.makeText(context, "Calibrated prototype '$label' saved offline", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = {
                Text(
                    "Offline Starter Templates",
                    color = UmbraColors.AccentAmber,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.axioloom.app.domain.templates.WorkspaceTemplates.ALL_TEMPLATES.forEach { tmpl ->
                        Card(
                            onClick = {
                                viewModel.loadTemplate(tmpl)
                                showTemplateDialog = false
                                Toast.makeText(context, "Loaded template: ${tmpl.title}", Toast.LENGTH_SHORT).show()
                            },
                            colors = CardDefaults.cardColors(containerColor = UmbraColors.BgElevated),
                            shape = RoundedCornerShape(3.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    tmpl.title,
                                    color = UmbraColors.AccentAmberDim,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    tmpl.description,
                                    color = UmbraColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCodeExportDialog && codeExportBlock != null) {
        CodeExportDialog(
            block = codeExportBlock!!,
            onDismiss = {
                showCodeExportDialog = false
                codeExportBlock = null
            }
        )
    }
}

/**
 * Unified Engineering Workbench Panel.
 * Hosts analytical workbench modules in a Swiss-grid matrix router with framed inspection well.
 */
@Composable
private fun EngineeringWorkbenchPanel(
    modifier: Modifier = Modifier,
    workspace: InkWorkspaceState,
    graph: ReactiveGraphSnapshot,
    recognitionMessages: Map<UUID, String>,
    selectedBlockId: UUID?,
    fastNumericOverrides: Map<UUID, FastNumericResult>,
    activeTransferFunction: TransferFunctionAnalysis?,
    active3DExpr: MathExpr,
    activePExpr: MathExpr,
    activeQExpr: MathExpr,
    boundValues: Map<String, Double>,
    activeFunctionDef: MathIntent.FunctionDef?,
    dimensionalWarnings: Map<UUID, List<com.axioloom.app.domain.units.DimensionalMismatchWarning>>,
    derivationSteps: List<DerivationStep>,
    isDerivationLoading: Boolean,
    activeTab: WorkbenchTab,
    onTabSelected: (WorkbenchTab) -> Unit,
    plotSubTab: PlotSubTab,
    onPlotSubTabSelected: (PlotSubTab) -> Unit,
    controlSubTab: ControlSubTab,
    onControlSubTabSelected: (ControlSubTab) -> Unit,
    onSelectBlock: (EquationBlock) -> Unit,
    onManualBlock: (EquationBlock) -> Unit,
    onToggleDisplayMode: (UUID) -> Unit,
    onSetTransform: (UUID, SymbolicTransform) -> Unit,
    onSetSolveTarget: (UUID, String) -> Unit,
    onGroupAsSystem: (List<UUID>) -> Unit,
    onParameterScrub: (String, Double) -> Unit,
    onParameterRelease: (UUID, String, Double) -> Unit,
    onSelectAlternative: (UUID, String) -> Unit,
    onGenerateCode: (EquationBlock) -> Unit,
    onSetToolMode: (CanvasToolMode) -> Unit,
    onSolveSchematic: () -> Unit,
    mnaSolver: com.axioloom.app.domain.circuit.SymbolicMnaSolver
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        // -----------------------------------------------------------------
        // Primary Option Tiles with Custom Tiny Vector Logos (Row 1)
        // -----------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WorkbenchTab.values().forEach { tab ->
                UmbraWorkbenchTile(
                    label = tab.label,
                    isActive = activeTab == tab,
                    onClick = { onTabSelected(tab) },
                    icon = { tint ->
                        when (tab) {
                            WorkbenchTab.INSPECTOR -> InspectorIcon(tint = tint)
                            WorkbenchTab.STEPS -> StepsIcon(tint = tint)
                            WorkbenchTab.PLOT -> PlotIcon(tint = tint)
                            WorkbenchTab.CONTROL -> ControlIcon(tint = tint)
                            WorkbenchTab.CIRCUIT -> CircuitIcon(tint = tint)
                            WorkbenchTab.RF_SMITH -> RfSmithIcon(tint = tint)
                            WorkbenchTab.KMAP -> KMapIcon(tint = tint)
                            WorkbenchTab.CODE -> CodeIcon(tint = tint)
                        }
                    }
                )
            }
        }

        // -----------------------------------------------------------------
        // Contextual Sub-Mode Tiles with Custom Vector Logos (Row 2)
        // -----------------------------------------------------------------
        if (activeTab == WorkbenchTab.PLOT) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlotSubTab.values().forEach { subTab ->
                    UmbraSubModeTile(
                        label = subTab.label,
                        isActive = plotSubTab == subTab,
                        onClick = { onPlotSubTabSelected(subTab) },
                        icon = { tint ->
                            when (subTab) {
                                PlotSubTab.CURVE_1D -> Curve1DIcon(tint = tint)
                                PlotSubTab.SURFACE_3D -> Surface3DIcon(tint = tint)
                                PlotSubTab.VECTOR -> VectorFieldIcon(tint = tint)
                                PlotSubTab.DSP -> DspStemIcon(tint = tint)
                                PlotSubTab.ODE -> OdeIcon(tint = tint)
                            }
                        }
                    )
                }
            }
        } else if (activeTab == WorkbenchTab.CONTROL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ControlSubTab.values().forEach { subTab ->
                    UmbraSubModeTile(
                        label = subTab.label,
                        isActive = controlSubTab == subTab,
                        onClick = { onControlSubTabSelected(subTab) },
                        icon = { tint ->
                            when (subTab) {
                                ControlSubTab.BODE -> BodeIcon(tint = tint)
                                ControlSubTab.ROOT_LOCUS -> RootLocusIcon(tint = tint)
                            }
                        }
                    )
                }
            }
        }

        // -----------------------------------------------------------------
        // Framed Inspection Well / Card (Umbra Section 5)
        // -----------------------------------------------------------------
        Surface(
            color = UmbraColors.BgRecessed,
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            Column(Modifier.fillMaxSize()) {
                // Inspection Well Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "fx |",
                            color = UmbraColors.TextSecondary,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif,
                            fontSize = 13.sp
                        )
                        Text(
                            text = when (activeTab) {
                                WorkbenchTab.INSPECTOR -> "INSPECTOR / SYMBOL GRAPH"
                                WorkbenchTab.STEPS -> "CALCULUS / DERIVATION STEPS"
                                WorkbenchTab.PLOT -> "MATH / VISUALIZATION"
                                WorkbenchTab.CONTROL -> "CONTROL / FREQUENCY ANALYSIS"
                                WorkbenchTab.RF_SMITH -> "RF / SMITH IMPEDANCE"
                                WorkbenchTab.CIRCUIT -> "CIRCUIT / SYMBOLIC MNA"
                                WorkbenchTab.KMAP -> "LOGIC / KARNAUGH SYNTHESIS"
                                WorkbenchTab.CODE -> "CODEGEN / EMBEDDED C99"
                            },
                            color = UmbraColors.TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            letterSpacing = 0.16.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(UmbraColors.AccentGreen, shape = CircleShape)
                    )
                }

                // Divider hairline
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(UmbraColors.BorderHairline)
                )

                // Active Tab Content Router
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    when (activeTab) {
                        WorkbenchTab.INSPECTOR -> {
                            InspectorTabContent(
                                workspace = workspace,
                                graph = graph,
                                recognitionMessages = recognitionMessages,
                                selectedBlockId = selectedBlockId,
                                fastNumericOverrides = fastNumericOverrides,
                                dimensionalWarnings = dimensionalWarnings,
                                onSelect = onSelectBlock,
                                onManual = onManualBlock,
                                onToggleDisplayMode = onToggleDisplayMode,
                                onSetTransform = onSetTransform,
                                onSetSolveTarget = onSetSolveTarget,
                                onGroupAsSystem = onGroupAsSystem,
                                onParameterScrub = onParameterScrub,
                                onParameterRelease = onParameterRelease,
                                onSelectAlternative = onSelectAlternative,
                                onGenerateCode = onGenerateCode
                            )
                        }
                        WorkbenchTab.STEPS -> {
                            StepsTabContent(
                                selectedBlock = workspace.blocks.firstOrNull { it.id == selectedBlockId },
                                steps = derivationSteps,
                                isLoading = isDerivationLoading
                            )
                        }
                        WorkbenchTab.PLOT -> {
                            Box(Modifier.fillMaxSize()) {
                                when (plotSubTab) {
                                    PlotSubTab.CURVE_1D -> {
                                        if (activeFunctionDef != null) {
                                            FunctionPlotCard(
                                                functionDef = activeFunctionDef,
                                                boundValues = boundValues,
                                                allFunctions = workspace.blocks.mapNotNull { it.intent as? MathIntent.FunctionDef },
                                                onDismiss = {}
                                            )
                                        } else {
                                            EmptyWorkbenchCard("Define a function f(x) = ... on the canvas to view 1D plot.")
                                        }
                                    }
                                    PlotSubTab.SURFACE_3D -> {
                                        Surface3DPlotCard(expr = active3DExpr, onClose = {})
                                    }
                                    PlotSubTab.VECTOR -> {
                                        VectorFieldPlotCard(pExpr = activePExpr, qExpr = activeQExpr, onClose = {})
                                    }
                                    PlotSubTab.DSP -> {
                                        DiscreteStemPlotCard(onClose = {})
                                    }
                                    PlotSubTab.ODE -> {
                                        OdeSimulationCard(onClose = {})
                                    }
                                }
                            }
                        }
                        WorkbenchTab.CONTROL -> {
                            Box(Modifier.fillMaxSize()) {
                                if (activeTransferFunction != null) {
                                    when (controlSubTab) {
                                        ControlSubTab.BODE -> TransferFunctionAnalyzerCard(analysis = activeTransferFunction, onDismiss = {})
                                        ControlSubTab.ROOT_LOCUS -> RootLocusNyquistCard(analysis = activeTransferFunction, onClose = {})
                                    }
                                } else {
                                    EmptyWorkbenchCard("Draw a transfer function rational expression in s (e.g. H(s) = 10 / (s^2 + 3s + 2)) to analyze Bode, Poles/Zeros, and Root-Locus.")
                                }
                            }
                        }
                        WorkbenchTab.RF_SMITH -> {
                            SmithChartCard(onClose = {})
                        }
                        WorkbenchTab.CIRCUIT -> {
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        onClick = { onSetToolMode(CanvasToolMode.SCHEMATIC) },
                                        color = UmbraColors.BgSurface,
                                        shape = RoundedCornerShape(3.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            SchematicIcon(tint = UmbraColors.TextSecondary)
                                            Text("Draw Schematic", fontSize = 11.sp, color = UmbraColors.TextSecondary)
                                        }
                                    }
                                    Surface(
                                        onClick = onSolveSchematic,
                                        color = UmbraColors.BgActiveAmber,
                                        shape = RoundedCornerShape(3.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderAmber),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Solve Schematic", color = UmbraColors.AccentAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    CircuitSolverSheet(solver = mnaSolver, onDismiss = {})
                                }
                            }
                        }
                        WorkbenchTab.KMAP -> {
                            KarnaughMapCard(onDismiss = {})
                        }
                        WorkbenchTab.CODE -> {
                            val targetBlock = workspace.blocks.firstOrNull { it.id == selectedBlockId }
                                ?: workspace.blocks.firstOrNull { it.intent != null }
                            if (targetBlock != null) {
                                EmbeddedCodeWorkbenchCard(block = targetBlock, onOpenFullExport = { onGenerateCode(targetBlock) })
                            } else {
                                EmptyWorkbenchCard("Select an equation block on the canvas to synthesize embedded C99 / Arduino / ESP32 code.")
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Umbra Component Archetypes: Tool Tab, Workbench Tile, Sub-mode Tile
// -------------------------------------------------------------------------

@Composable
fun UmbraToolTab(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    icon: (@Composable (tint: Color) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textColor = if (isActive) UmbraColors.TextPrimary else UmbraColors.TextSecondary
    val iconColor = if (isActive) UmbraColors.AccentAmber else UmbraColors.TextSecondary

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = if (isActive) 2.dp else 0.dp)
        ) {
            icon?.invoke(iconColor)
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
            )
        }
        // Directional 2px solid amber underline flush against bottom hairline
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(UmbraColors.AccentAmber)
            )
        }
    }
}

@Composable
fun UmbraWorkbenchTile(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isActive) UmbraColors.BgActiveAmber else UmbraColors.BgSurface
    val borderColor = if (isActive) UmbraColors.BorderAmber else UmbraColors.BorderSubtle
    val textColor = if (isActive) UmbraColors.TextPrimary else UmbraColors.TextSecondary
    val iconColor = if (isActive) UmbraColors.AccentAmber else UmbraColors.TextSecondary

    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier.height(44.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxHeight()
        ) {
            // Umbra 3px Directional Amber Latch Bar
            if (isActive) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(UmbraColors.AccentAmber)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(
                    start = if (isActive) 10.dp else 12.dp, // Compensated for latch bar
                    end = 12.dp
                )
            ) {
                icon(iconColor)
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun UmbraSubModeTile(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isActive) UmbraColors.BgActiveAmber else UmbraColors.BgSurface
    val borderColor = if (isActive) UmbraColors.BorderAmber else UmbraColors.BorderSubtle
    val textColor = if (isActive) UmbraColors.TextPrimary else UmbraColors.TextSecondary
    val iconColor = if (isActive) UmbraColors.AccentAmber else UmbraColors.TextSecondary

    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier.height(34.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxHeight()
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(UmbraColors.AccentAmber)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(
                    start = if (isActive) 8.dp else 10.dp,
                    end = 10.dp
                )
            ) {
                icon(iconColor)
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun EmptyWorkbenchCard(message: String) {
    Surface(
        color = UmbraColors.BgElevated,
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                text = message,
                color = UmbraColors.TextMuted,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun StepsTabContent(
    selectedBlock: EquationBlock?,
    steps: List<DerivationStep>,
    isLoading: Boolean
) {
    Surface(
        color = UmbraColors.BgElevated,
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text(
                text = "Step-by-Step Derivation" + (if (selectedBlock != null) " · Block ${selectedBlock.id.toString().take(6)}" else ""),
                color = UmbraColors.AccentAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Deriving intermediate calculus steps...", color = UmbraColors.AccentAmberDim, fontSize = 12.sp)
                }
            } else if (steps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No derivation steps computed. Tap an equation block on the canvas to deconstruct its antiderivative, characteristic polynomial, or solve steps.",
                        color = UmbraColors.TextMuted,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(steps) { step ->
                        Surface(
                            color = UmbraColors.BgSurface,
                            shape = RoundedCornerShape(3.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(step.title, color = UmbraColors.AccentAmberDim, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Spacer(Modifier.height(2.dp))
                                Text(step.latexExpression, color = UmbraColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbeddedCodeWorkbenchCard(
    block: EquationBlock,
    onOpenFullExport: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var precision by remember { mutableStateOf(CPrecision.FLOAT) }

    val codePreview = remember(block.intent, precision) {
        val intent = block.intent
        when {
            intent is MathIntent.EvaluationQuery -> {
                EmbeddedCGenerator.generateMathFunction(intent.expression, functionName = "eval_math", precision = precision)
            }
            intent is MathIntent.Assignment -> {
                EmbeddedCGenerator.generateMathFunction(intent.rhs, functionName = "calculate_${intent.variableName}", precision = precision)
            }
            intent is MathIntent.FunctionDef -> {
                EmbeddedCGenerator.generateMathFunction(intent.body, functionName = intent.funcName, precision = precision)
            }
            else -> null
        }
    }

    Surface(
        color = UmbraColors.BgElevated,
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Embedded C99 / ESP32 Synthesizer", color = UmbraColors.AccentAmberDim, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = precision == CPrecision.FLOAT,
                        onClick = { precision = CPrecision.FLOAT },
                        label = { Text("float", fontSize = 10.sp) }
                    )
                    FilterChip(
                        selected = precision == CPrecision.DOUBLE,
                        onClick = { precision = CPrecision.DOUBLE },
                        label = { Text("double", fontSize = 10.sp) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            if (codePreview != null) {
                Surface(
                    color = UmbraColors.BgRecessed,
                    shape = RoundedCornerShape(3.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            text = codePreview.sourceCode,
                            color = Color(0xFF81C784),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(codePreview.sourceCode))
                        Toast.makeText(context, "Copied C99 source to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy C Source", color = UmbraColors.AccentAmber, fontSize = 11.sp)
                    }
                    TextButton(onClick = onOpenFullExport) {
                        Text("Full .h / .ino Dialog →", color = UmbraColors.AccentAmberDim, fontSize = 11.sp)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select a formula or transfer function to synthesize firmware code.", color = UmbraColors.TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InspectorTabContent(
    workspace: InkWorkspaceState,
    graph: ReactiveGraphSnapshot,
    recognitionMessages: Map<UUID, String>,
    selectedBlockId: UUID?,
    fastNumericOverrides: Map<UUID, FastNumericResult>,
    dimensionalWarnings: Map<UUID, List<com.axioloom.app.domain.units.DimensionalMismatchWarning>>,
    onSelect: (EquationBlock) -> Unit,
    onManual: (EquationBlock) -> Unit,
    onToggleDisplayMode: (UUID) -> Unit,
    onSetTransform: (UUID, SymbolicTransform) -> Unit,
    onSetSolveTarget: (UUID, String) -> Unit,
    onGroupAsSystem: (List<UUID>) -> Unit,
    onParameterScrub: (String, Double) -> Unit,
    onParameterRelease: (UUID, String, Double) -> Unit,
    onSelectAlternative: (UUID, String) -> Unit,
    onGenerateCode: (EquationBlock) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        val selectedBlock = workspace.blocks.firstOrNull { it.id == selectedBlockId }
        if (selectedBlock != null) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                // Parameter Slider Scrubbing for variable assignments
                val assignment = selectedBlock.intent as? MathIntent.Assignment
                val scalarVal = if (assignment != null) {
                    fastNumericOverrides[selectedBlock.id]?.numericValue
                        ?: graph.blocks[selectedBlock.id]?.result?.expression?.toDoubleOrNull()
                        ?: (assignment.rhs as? MathExpr.Number)?.value?.toDoubleOrNull()
                } else null

                if (assignment != null && scalarVal != null) {
                    ParameterSliderPill(
                        variableName = assignment.variableName,
                        initialValue = scalarVal,
                        onValueChange = { newVal -> onParameterScrub(assignment.variableName, newVal) },
                        onValueChangeFinished = { newVal -> onParameterRelease(selectedBlock.id, assignment.variableName, newVal) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Block: ${selectedBlock.id.toString().take(8)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = UmbraColors.AccentAmber
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onGenerateCode(selectedBlock) }) {
                            Text("C Code", color = UmbraColors.AccentAmberDim, style = MaterialTheme.typography.labelSmall)
                        }
                        FilterChip(
                            selected = selectedBlock.displayMode == BlockDisplayMode.TYPESET,
                            onClick = { onToggleDisplayMode(selectedBlock.id) },
                            label = { Text(if (selectedBlock.displayMode == BlockDisplayMode.TYPESET) "Typeset" else "Handwritten", fontSize = 10.sp) }
                        )
                    }
                }

                // Symbolic Transform Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SymbolicTransform.values().forEach { xform ->
                        val label = when (xform) {
                            SymbolicTransform.AUTO -> "Auto"
                            SymbolicTransform.SIMPLIFY -> "Simplify"
                            SymbolicTransform.FACTOR -> "Factor"
                            SymbolicTransform.EXPAND -> "Expand"
                            SymbolicTransform.PARTIAL_FRACTIONS -> "Apart"
                            SymbolicTransform.TOGETHER -> "Together"
                            SymbolicTransform.TRIG_EXPAND -> "TrigExpand"
                            SymbolicTransform.TRIG_REDUCE -> "TrigReduce"
                            SymbolicTransform.TAYLOR_SERIES -> "Series (O^5)"
                        }
                        FilterChip(
                            selected = selectedBlock.transform == xform,
                            onClick = { onSetTransform(selectedBlock.id, xform) },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
            }
        }

        if (workspace.blocks.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Write on the canvas. Double-tap a block to toggle Typeset rendering.",
                color = UmbraColors.TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(workspace.blocks, key = { it.id.toString() }) { block ->
                    val graphBlock = graph.blocks[block.id]
                    val status = graphBlock?.status ?: block.status
                    val result = graphBlock?.result ?: block.result
                    val message = recognitionMessages[block.id]
                    val isSelected = block.id == selectedBlockId

                    Surface(
                        color = if (isSelected) UmbraColors.BgActiveAmber else UmbraColors.BgElevated,
                        shape = RoundedCornerShape(3.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) UmbraColors.BorderAmber else UmbraColors.BorderSubtle
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .combinedClickable(
                                onClick = { onSelect(block) },
                                onDoubleClick = { onToggleDisplayMode(block.id) },
                                onLongClick = { onManual(block) }
                            )
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${block.id.toString().take(6)} · ${statusLabel(status)}",
                                    color = when {
                                        isSelected -> UmbraColors.AccentAmber
                                        status is BlockStatus.Error -> UmbraColors.AccentDanger
                                        else -> UmbraColors.TextSecondary
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (block.intent != null) {
                                    Text("intent ready", color = Color(0xFF81C784), fontSize = 10.sp)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "LaTeX: ${block.recognizedLatex ?: "recognizing..."}",
                                color = UmbraColors.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                maxLines = 2
                            )
                            if (block.alternatives.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Alt:", style = MaterialTheme.typography.labelSmall, color = UmbraColors.AccentAmberDim)
                                    block.alternatives.forEach { alt ->
                                        FilterChip(
                                            selected = false,
                                            onClick = { onSelectAlternative(block.id, alt) },
                                            label = { Text(alt, fontSize = 9.sp) }
                                        )
                                    }
                                }
                            }
                            if (block.candidateSolveVars.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    block.candidateSolveVars.forEach { varName ->
                                        FilterChip(
                                            selected = block.solveTarget == varName,
                                            onClick = { onSetSolveTarget(block.id, varName) },
                                            label = { Text("Solve for $varName", fontSize = 9.sp) }
                                        )
                                    }
                                }
                            }
                            message?.let {
                                Text("Provenance: $it", color = Color(0xFF64B5F6), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            val dWarns = dimensionalWarnings[block.id]
                            if (!dWarns.isNullOrEmpty()) {
                                Text("Unit Mismatch: ${dWarns.first().message}", color = UmbraColors.AccentDanger, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            val fastOverride = fastNumericOverrides[block.id]
                            if (fastOverride != null) {
                                Text("= ${fastOverride.formattedPreview} (live)", color = UmbraColors.AccentAmberDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            } else if (result != null) {
                                Text("= ${result.expression}", color = UmbraColors.TextPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            } else if (status is BlockStatus.Error) {
                                Text(status.message, color = UmbraColors.AccentDanger, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualLatexDialog(
    block: EquationBlock,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Boolean
) {
    var latex by remember(block.id, block.revision) { mutableStateOf(block.recognizedLatex.orEmpty()) }
    var error by remember(block.id, block.revision) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual LaTeX input") },
        text = {
            Column {
                Text("Block ${block.id.toString().take(8)} · revision ${block.revision}", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = latex,
                    onValueChange = { latex = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("LaTeX") },
                    placeholder = { Text("\\frac{x^2}{2} = 4") },
                    supportingText = { Text("This is manual test input, not model recognition.") },
                    minLines = 2,
                    maxLines = 4
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (latex.isBlank()) {
                    error = "LaTeX cannot be empty"
                } else {
                    val accepted = onSubmit(latex)
                    if (!accepted) error = "Rejected: block revision changed"
                }
            }) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun statusLabel(status: BlockStatus): String = when (status) {
    is BlockStatus.Dirty -> "processing"
    is BlockStatus.Ready -> "ready"
    is BlockStatus.Error -> "error"
}

@Composable
private fun ModelStatusDialog(
    status: ExternalModelStatus,
    onDismiss: () -> Unit,
    onClearCustom: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HMER Model Status", color = UmbraColors.AccentAmber) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Active Source: ${status.activeSource.name}", fontWeight = FontWeight.Bold)
                if (status.hasCustomModel) {
                    Text("Custom Model: ${status.customModelPath ?: "Loaded"}", style = MaterialTheme.typography.bodySmall)
                    Text("Vocabulary Tokens: ${status.customVocabCount}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Using bundled default APK assets (hmer/hmer_vocab.txt) with Prototype Elastic DTW fallback.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = if (status.hasCustomModel) {
            { TextButton(onClick = { onClearCustom(); onDismiss() }) { Text("Revert to Bundled", color = UmbraColors.AccentDanger) } }
        } else null
    )
}

@Composable
private fun HandwritingCalibrationDialog(
    onDismiss: () -> Unit,
    onSavePrototype: (label: String, latex: String, strokes: List<List<Offset>>) -> Unit
) {
    var symbolLabel by remember { mutableStateOf("x") }
    var symbolLatex by remember { mutableStateOf("x") }
    val currentStrokes = remember { androidx.compose.runtime.mutableStateListOf<List<Offset>>() }
    val activeStroke = remember { androidx.compose.runtime.mutableStateListOf<Offset>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate Handwriting Symbol", color = UmbraColors.AccentAmber) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Draw a canonical exemplar for this symbol. It will be stored offline in user_symbol_prototypes.json and used as high-priority DTW prototype.",
                    style = MaterialTheme.typography.bodySmall,
                    color = UmbraColors.TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = symbolLabel,
                        onValueChange = {
                            symbolLabel = it
                            if (symbolLatex == symbolLabel.dropLast(1)) symbolLatex = it
                        },
                        label = { Text("Label") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = symbolLatex,
                        onValueChange = { symbolLatex = it },
                        label = { Text("LaTeX") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Drawing Canvas
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = UmbraColors.BgRecessed,
                    border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        activeStroke.clear()
                                        activeStroke.add(offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        activeStroke.add(change.position)
                                    },
                                    onDragEnd = {
                                        if (activeStroke.isNotEmpty()) {
                                            currentStrokes.add(activeStroke.toList())
                                            activeStroke.clear()
                                        }
                                    },
                                    onDragCancel = {
                                        activeStroke.clear()
                                    }
                                )
                            }
                    ) {
                        for (stroke in currentStrokes) {
                            if (stroke.size >= 2) {
                                for (i in 1 until stroke.size) {
                                    drawLine(
                                        color = UmbraColors.AccentAmber,
                                        start = stroke[i - 1],
                                        end = stroke[i],
                                        strokeWidth = 5f,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }
                        if (activeStroke.size >= 2) {
                            for (i in 1 until activeStroke.size) {
                                drawLine(
                                    color = UmbraColors.AccentAmberDim,
                                    start = activeStroke[i - 1],
                                    end = activeStroke[i],
                                    strokeWidth = 5f,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${currentStrokes.size} stroke(s) recorded",
                        style = MaterialTheme.typography.labelSmall,
                        color = UmbraColors.TextMuted
                    )
                    TextButton(onClick = { currentStrokes.clear(); activeStroke.clear() }) {
                        Text("Clear", color = UmbraColors.AccentDanger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (currentStrokes.isNotEmpty() && symbolLabel.isNotBlank()) {
                        onSavePrototype(symbolLabel.trim(), symbolLatex.trim(), currentStrokes.toList())
                        onDismiss()
                    }
                },
                enabled = currentStrokes.isNotEmpty() && symbolLabel.isNotBlank()
            ) {
                Text("Save Prototype", color = UmbraColors.AccentAmber)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
