import React from "react";
import {BaseScreen} from "../baseScreen";
import api from "../../../api";
import {ProgressComponent, ProgressTracker, Round2DP} from "../../progress";
import {getFormatName, getVersionName} from "../mode/modeOption";
import {SaveScreen} from "../save/saveScreen";

const ARCHIVE_EXTENSIONS = [".zip", ".mcworld", ".mctemplate", ".tar", ".tar.gz", ".tgz", ".tar.br", ".tbr"];

/**
 * Lets the user pick several worlds (any edition / version) and merge them into one seamless world.
 */
export class MergeScreen extends BaseScreen {
    mergeProgress = new ProgressTracker("Merging worlds", (newState) => this.setState({mergeProgress: newState}), this.app.cancelTask);
    state = {
        worlds: [],
        writers: [],
        outputType: undefined,
        adding: undefined,
        addProgress: 0,
        merging: false,
        mergeProgress: this.mergeProgress.state,
        options: {
            gapChunks: 32,
            seamWidthChunks: 16,
            seams: true,
            belowZero: true,
            keepNbt: true,
            layout: "GRID",
            alignRegions: 1,
            javaBlending: false,
            between: "BLENDED",
            outputName: "Merged World"
        }
    };
    fileInput = undefined;
    folderInput = undefined;

    constructor(props) {
        super(props);
        this.fileInput = document.createElement("input");
        this.fileInput.type = "file";
        this.fileInput.accept = ARCHIVE_EXTENSIONS.join(",");
        this.fileInput.onclick = () => this.fileInput.value = null;
        this.fileInput.onchange = () => {
            let file = this.fileInput.files[0];
            if (file) this.addWorld(window.chunker.getPathForFile(file), file.name);
        };

        this.folderInput = document.createElement("input");
        this.folderInput.type = "file";
        this.folderInput.webkitdirectory = true;
        this.folderInput.directory = true;
        this.folderInput.multiple = true;
        this.folderInput.onclick = () => this.folderInput.value = null;
        this.folderInput.onchange = () => {
            let files = Array.from(this.folderInput.files);
            let level = files.find(file => file.webkitRelativePath.endsWith("/level.dat")
                && file.webkitRelativePath.split("/").length === 2);
            if (!level) {
                this.app.showError("Invalid World", "The folder you selected did not contain a level.dat, please ensure you're using a Minecraft world folder.", null, undefined, true);
                return;
            }
            let fullPath = window.chunker.getPathForFile(level);
            this.addWorld(fullPath.substring(0, fullPath.lastIndexOf("level.dat")), level.webkitRelativePath.split("/")[0]);
        };
    }

    getStage = () => this.state?.merging ? 3 : 1;

    componentDidMount() {
        super.componentDidMount();
        if (!api.isConnected()) {
            api.connect((errorCode) => {
                if (!api.isConnected()) {
                    this.showBackendError(errorCode);
                }
            });
        }
    }

    /**
     * Explain why the backend stopped. The connect callback also fires when the backend dies part way through a merge,
     * so this reports the real cause (out of memory, killed...) rather than a generic connection failure.
     */
    showBackendError(errorCode) {
        let merging = this.state.merging;
        if (merging) {
            this.setState({merging: false});
            this.app.setStage(1);
        }
        let title = merging ? "Merge stopped" : "Failed to connect to backend";
        if (errorCode === 12) {
            this.app.showError("Out of memory", "Chunker ran out of memory while merging. Close other programs and try again, merge fewer or smaller worlds at once, or use the command-line merge with more memory (for example java -Xmx8G -jar chunker-cli.jar merge ...).", null, undefined, false, true);
        } else if (errorCode === -100) {
            this.app.showError(title, "Unable to start the chunker-cli backend (it may be missing from the build). Please try closing Chunker and opening it again.", null, undefined, false, true);
        } else if (errorCode === 1) {
            this.app.showError(title, "The backend process stopped unexpectedly (exit code 1). The details are in Chunker's log file (main.log in the Chunker logs folder).", null, undefined, false, true);
        } else {
            this.app.showError(title, "The backend process stopped unexpectedly (exit code " + errorCode + "). The details are in Chunker's log file (main.log in the Chunker logs folder).", null, undefined, false, true);
        }
    }

    send = (message, handler) => {
        if (api.isConnected()) {
            api.send(message, handler);
        } else {
            // Wait for the connection (it's opened on mount)
            setTimeout(() => this.send(message, handler), 250);
        }
    };

    addWorld = (path, displayName) => {
        if (this.state.adding) return;
        let lower = path.toLowerCase();
        let isArchive = ARCHIVE_EXTENSIONS.some(extension => lower.endsWith(extension));
        if (!isArchive && !lower.endsWith("/") && !lower.endsWith("\\")) {
            // Folder paths come from the level.dat location so always end with a separator
            this.app.showError("Unsupported file", "Only world folders and .zip, .mcworld, .mctemplate and .tar archives can be merged.", undefined, undefined, true);
            return;
        }
        this.setState({adding: displayName, addProgress: 0});
        this.send({type: "flow", method: "add_merge_world", path: path}, (message) => {
            if (message.type === "progress") {
                this.setState({addProgress: message.percentage * 100});
            } else if (message.type === "progress_state") {
                this.setState({addProgress: 99.9});
            } else if (message.type === "response") {
                let world = {
                    id: message.output.mergeId,
                    name: message.output.name || displayName,
                    input: message.output.version.input
                };
                this.setState((state) => {
                    let worlds = [...state.worlds, world];
                    let writers = state.writers.length > 0 ? state.writers : message.output.version.writers;
                    return {
                        worlds: worlds,
                        writers: writers,
                        outputType: state.outputType ?? world.input.id,
                        adding: undefined
                    };
                });
            } else {
                this.setState({adding: undefined});
                this.app.showError("Failed to add world", message.error ?? "Something went wrong reading the world.", message.errorId, message.stackTrace, true);
            }
        });
    };

    removeWorld = (id) => {
        this.send({type: "flow", method: "remove_merge_world", id: id}, (message) => {
            if (message.type === "response") {
                this.setState((state) => ({worlds: message.output.map(worldId => state.worlds.find(world => world.id === worldId)).filter(Boolean)}));
            }
        });
    };

    moveWorld = (id, offset) => {
        this.send({type: "flow", method: "move_merge_world", id: id, offset: offset}, (message) => {
            if (message.type === "response") {
                this.setState((state) => ({worlds: message.output.map(worldId => state.worlds.find(world => world.id === worldId)).filter(Boolean)}));
            }
        });
    };

    setOption = (name, value) => {
        this.setState((state) => ({options: {...state.options, [name]: value}}));
    };

    merge = () => {
        let writer = this.state.writers.find(entry => entry.id === this.state.outputType);
        if (!writer) return;
        this.app.setState({outputType: writer, inputType: this.state.worlds[0].input});
        this.setState({merging: true}, () => this.app.setStage(3));

        let options = {...this.state.options};
        if (writer.id.startsWith("JAVA") && options.between === "GAME") {
            // Leave the space between worlds to the game: a narrow blended ring, no filled gaps, blending data on
            options.seamWidthChunks = 3;
            options.maxCorridorChunks = 0;
            options.javaBlending = true;
        }
        this.send({
            type: "flow",
            method: "merge",
            outputType: writer.id,
            ...options
        }, this.mergeProgress.pipe((message) => {
            if (message.type === "response") {
                this.app.setState({convertResult: message.output});
                this.app.setScreen(SaveScreen);
            } else if (message.type === "error") {
                this.setState({merging: false});
                this.app.setStage(1);
                if (!message.cancelled) {
                    this.app.showError("Failed to merge worlds", message.error, message.errorId, message.stackTrace, true);
                }
            }
        }));
    };

    renderWorld(world, index) {
        let first = index === 0;
        return (
            <div className="merge_world gray_box" key={world.id}>
                <div className="merge_world_info">
                    <strong>{world.name}</strong>
                    <span className="version">{getFormatName(world.input.id)} {getVersionName(world.input.id)}</span>
                    {first && <span className="merge_main">Main world (keeps spawn &amp; player)</span>}
                </div>
                <div className="merge_world_actions">
                    <button className="button" disabled={first} onClick={() => this.moveWorld(world.id, -1)} title="Move up">▲</button>
                    <button className="button" disabled={index === this.state.worlds.length - 1} onClick={() => this.moveWorld(world.id, 1)} title="Move down">▼</button>
                    <button className="button red" onClick={() => this.removeWorld(world.id)} title="Remove">✕</button>
                </div>
            </div>
        );
    }

    renderOptions() {
        let options = this.state.options;
        let numberInput = (name, label, min, max, help) => (
            <label className="merge_option">
                <span>{label}</span>
                <input type="number" min={min} max={max} value={options[name]}
                       onChange={(e) => this.setOption(name, Math.max(min, Math.min(max, parseInt(e.target.value) || min)))}/>
                <small>{help}</small>
            </label>
        );
        let checkbox = (name, label, help) => (
            <label className="merge_option merge_checkbox">
                <input type="checkbox" checked={options[name]} onChange={(e) => this.setOption(name, e.target.checked)}/>
                <span>{label}</span>
                <small>{help}</small>
            </label>
        );
        let javaOutput = typeof this.state.outputType === "string" && this.state.outputType.startsWith("JAVA");
        let gameFills = javaOutput && options.between === "GAME";
        let groups = {};
        for (let writer of this.state.writers) {
            let format = writer.type || writer.id.split("_")[0];
            (groups[format] = groups[format] || []).push(writer);
        }
        return (
            <div className="merge_options">
                <label className="merge_option">
                    <span>Output format</span>
                    <select value={this.state.outputType} onChange={(e) => this.setState({outputType: e.target.value})}>
                        {Object.entries(groups).map(([format, writers]) => (
                            <optgroup key={format} label={getFormatName(format)}>
                                {writers.map(writer => (
                                    <option key={writer.id} value={writer.id}>{writer.label} {writer.version ?? getVersionName(writer.id)}</option>
                                ))}
                            </optgroup>
                        ))}
                    </select>
                </label>
                <label className="merge_option">
                    <span>World name</span>
                    <input type="text" value={options.outputName} onChange={(e) => this.setOption("outputName", e.target.value)}/>
                </label>
                <label className="merge_option">
                    <span>Layout</span>
                    <select value={options.layout} onChange={(e) => this.setOption("layout", e.target.value)}>
                        <option value="GRID">Grid</option>
                        <option value="ROW">Single row</option>
                    </select>
                </label>
                {numberInput("gapChunks", "Gap between worlds (chunks)", 0, 1024, "Rounded up to whole regions (32 chunks).")}
                <label className="merge_option">
                    <span>Between worlds</span>
                    <select value={javaOutput ? options.between : "BLENDED"} disabled={!javaOutput}
                            onChange={(e) => this.setOption("between", e.target.value)}>
                        <option value="BLENDED">Generated by Chunker (Java and Bedrock)</option>
                        <option value="GAME">Left for Minecraft to generate (Java only, experimental)</option>
                    </select>
                    <small>{gameFills
                        ? "Only a narrow blended ring is made around each world. Minecraft generates the rest (with villages, structures and caves) when you explore it, blending its new terrain into the ring."
                        : "Chunker fills the space between worlds with terrain, plants and trees that blend into each world."}</small>
                </label>
                {checkbox("seams", "Blend borders", "Generate terrain between worlds that blends heights, materials, biomes and plants.")}
                {options.seams && !gameFills && numberInput("seamWidthChunks", "Blend distance (chunks)", 1, 128, "How far blended terrain reaches out from each world.")}
                {checkbox("belowZero", "Generate below Y=0 for old worlds", "Adds deepslate, caves and ores under worlds from before 1.18.")}
                {checkbox("keepNbt", "Keep exact data for same-edition worlds", "Copies original mob / block data when the world is the same edition as the output.")}
                {numberInput("alignRegions", "Placement alignment (regions)", 1, 64, "Use 8 to keep Nether portals linked exactly.")}
                {javaOutput && !gameFills && checkbox("javaBlending", "Java game blending (experimental)", "Lets Java blend terrain it generates later into the merged world.")}
            </div>
        );
    }

    render() {
        if (this.state.merging) {
            return (
                <div className="maincol">
                    <div className="topbar">
                        <h1>Merging Worlds</h1>
                        <h2>Combining {this.state.worlds.length} worlds into one.</h2>
                    </div>
                    <div className="main_content main_content_progress">
                        <ProgressComponent progress={this.mergeProgress} cancel={false}/>
                    </div>
                    <div className="bottombar">
                        <button className="button red" onClick={() => this.mergeProgress.cancel()}>Cancel</button>
                        <button className="button green" disabled="disabled">Merging...</button>
                    </div>
                </div>
            );
        }

        return (
            <div className="maincol">
                <div className="topbar">
                    <h1>Merge Worlds</h1>
                    <h2>Add Java or Bedrock worlds of any version. The first world is the main world.</h2>
                </div>
                <div className="main_content merge_content">
                    <div className="merge_worlds">
                        {this.state.worlds.map((world, index) => this.renderWorld(world, index))}
                        {this.state.adding &&
                            <div className="merge_world gray_box">
                                <div className="merge_world_info">
                                    <strong>{this.state.adding}</strong>
                                    <span>Preparing: {Round2DP(this.state.addProgress)}%</span>
                                </div>
                            </div>
                        }
                        <div className="select_world_options">
                            <button onClick={() => this.folderInput.click()} className="gray_box" disabled={!!this.state.adding}>
                                Add world folder
                                <span>Select a world folder</span>
                            </button>
                            <button onClick={() => this.fileInput.click()} className="gray_box" disabled={!!this.state.adding}>
                                Add archive
                                <span>.zip, .mcworld, .mctemplate, .tar</span>
                            </button>
                        </div>
                    </div>
                    {this.state.worlds.length > 0 && this.renderOptions()}
                </div>
                <div className="bottombar">
                    <button className="button red" onClick={() => window.location.reload()}>Back</button>
                    <button className="button green"
                            disabled={this.state.worlds.length < 2 || !!this.state.adding || !this.state.outputType}
                            onClick={this.merge}>Merge {this.state.worlds.length} worlds
                    </button>
                </div>
            </div>
        );
    }
}
