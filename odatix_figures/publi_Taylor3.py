import glob
import os
import re
import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns
import yaml

# Navigate to the generic folder: cd /DDS
# Activate the virtual environment: source venv/bin/activate
# Navigate to the parent folder: cd /trace_odatix
# Execution command: python3 publi_Taylor3.py

# ==========================================
# 1. PARAMÈTRES DU SCRIPT
# ==========================================
DOSSIER_ARCHI = "Publi_Taylor3_V3"

DISPLAY_NAME = "Taylor Series (3rd Order)"

sns.set_theme(style="whitegrid")
plt.rcParams.update({"font.size": 11, "axes.labelsize": 13})

# ==========================================
# 2. EXTRACTION ET PARSING YAML
# ==========================================
def load_data_from_yamls(file_paths):
    rows = []
    global_units = {}

    for path in file_paths:
        if not os.path.isfile(path):
            print(f"Avertissement : fichier introuvable -> {path}")
            continue

        with open(path, "r") as file:
            data = yaml.safe_load(file)

        if not data:
            continue

        units = data.get("units", {})
        global_units.update(units)
        fmax_results = data.get("fmax_synthesis", {})

        for techno, archs in fmax_results.items():
            for arch, segs_dict in archs.items():
                
                # Extraction du nombre de bits situé après l'underscore
                match_bits = re.search(r"_(\d+)", arch)
                if match_bits:
                    bit_val = int(match_bits.group(1))
                else:
                    nums = re.findall(r"\d+", arch)
                    bit_val = int(nums[-1]) if nums else 0

                clean_label = f"{bit_val}-bit" if bit_val > 0 else arch

                for seg_str, metrics in segs_dict.items():
                    if not metrics:
                        continue

                    match_seg = re.search(r"(\d+)", str(seg_str))
                    seg_val = int(match_seg.group(1)) if match_seg else int(seg_str)

                    rows.append({
                        "Technology": techno,
                        "Architecture": arch,
                        "Bits": bit_val,
                        "Label": clean_label,
                        "Segments": seg_val,
                        "Fmax": metrics.get("Fmax"),
                        "Cell_Area": metrics.get("Cell_Area"),
                        "Total_Power": metrics.get("Total_Power"),
                    })

    df = pd.DataFrame(rows)

    if not df.empty:
        if "Cell_Area" in df.columns:
            df["Cell_Area"] = df["Cell_Area"] / 1_000_000.0  # um² -> mm²
        df = df.sort_values(by=["Bits", "Segments"])

    global_units["Cell_Area"] = "mm²"
    return df, global_units

# ==========================================
# 3. TRACÉ COMBINÉ
# ==========================================
def plot_combined_figure(df, area_unit, fmax_unit, power_unit, output_dir, arch_folder, display_name):
    target_techno = "TSMC_N28_HPCplus_P140_12T_0V9"
    df_0v9 = df[df["Technology"] == target_techno].copy()

    if df_0v9.empty:
        print(f"Aucune donnée trouvée pour la technologie : {target_techno}")
        return

    fig, (ax1, ax3) = plt.subplots(2, 1, figsize=(10, 9), sharex=True)

    # --- SUBPLOT (a) : Area (Gauche) et Total Power (Droite) ---
    ax1.set_title("(a)", loc="left", fontweight="bold", fontsize=14)
    ax1.set_ylabel(f"Logic Area ({area_unit})", fontweight="bold", color="tab:blue")

    ax2 = ax1.twinx()
    ax2.set_ylabel(f"Total Power ({power_unit})", fontweight="bold", color="tab:green")
    ax2.grid(False)

    # --- SUBPLOT (b) : Fmax ---
    ax3.set_title("(b)", loc="left", fontweight="bold", fontsize=14)
    ax3.set_xlabel("Number of Segments (Partitioning)", fontweight="bold")
    ax3.set_ylabel(f"Maximum Frequency Fmax ({fmax_unit})", fontweight="bold", color="tab:red")

    markers = ["o", "s", "^", "D"]
    linestyles = ["-", "--", ":", "-."]
    
    unique_archs = df_0v9.sort_values(by="Bits")["Architecture"].unique()
    lines = []

    for idx, arch_key in enumerate(unique_archs):
        subset = df_0v9[df_0v9["Architecture"] == arch_key]
        if subset.empty:
            continue

        lbl = subset["Label"].iloc[0]
        marker = markers[idx % len(markers)]
        ls = linestyles[idx % len(linestyles)]

        # Area (Bleu) avec parenthèses
        l1, = ax1.plot(subset["Segments"], subset["Cell_Area"], color="tab:blue",
                       marker=marker, linestyle=ls, linewidth=2,
                       label=f"Area ({lbl})")
        # Power (Vert) avec parenthèses
        l2, = ax2.plot(subset["Segments"], subset["Total_Power"], color="tab:green",
                       marker=marker, linestyle=ls, linewidth=2,
                       label=f"Power ({lbl})")
        # Fmax (Rouge) avec parenthèses
        l3, = ax3.plot(subset["Segments"], subset["Fmax"], color="tab:red",
                       marker=marker, linestyle=ls, linewidth=2,
                       label=f"Fmax ({lbl})")

        lines.extend([l1, l2, l3])

    ax1.tick_params(axis="y", labelcolor="tab:blue")
    ax2.tick_params(axis="y", labelcolor="tab:green")
    ax3.tick_params(axis="y", labelcolor="tab:red")

    unique_segs = sorted(df_0v9["Segments"].unique())
    ax3.set_xticks(unique_segs)
    ax3.set_xticklabels([str(s) for s in unique_segs])

    # Titre et légende globale
    labels = [l.get_label() for l in lines]
    fig.suptitle(f"Architecture: {display_name}", fontweight="bold", y=0.98)
    fig.legend(lines, labels, loc="upper center", bbox_to_anchor=(0.5, 0.94), ncol=3, frameon=True)

    fig.tight_layout(rect=[0, 0, 1, 0.86])

    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, f"{arch_folder}_Combined_Metrics_0V9.pdf")

    plt.savefig(output_path, dpi=300, format="pdf", bbox_inches="tight")
    print(f"Graphique sauvegardé dans : {output_path}")

    if "agg" not in plt.get_backend().lower():
        plt.show()
    plt.close()

# ==========================================
# 4. EXÉCUTION
# ==========================================
if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))

    if os.path.isabs(DOSSIER_ARCHI):
        arch_dir = DOSSIER_ARCHI
    else:
        arch_dir = os.path.join(script_dir, DOSSIER_ARCHI)
        if not os.path.isdir(arch_dir):
            arch_dir = os.path.abspath(DOSSIER_ARCHI)

    if not os.path.isdir(arch_dir):
        raise FileNotFoundError(f"Dossier introuvable : {arch_dir}")

    yaml_files = sorted(
        glob.glob(os.path.join(arch_dir, "*.yml")) +
        glob.glob(os.path.join(arch_dir, "*.yaml"))
    )

    if not yaml_files:
        raise FileNotFoundError(f"Aucun fichier YAML trouvé dans : {arch_dir}")

    print(f"Dossier cible : {arch_dir}")
    for yf in yaml_files:
        print(f"  - {os.path.basename(yf)}")

    df_results, units = load_data_from_yamls(yaml_files)

    if not df_results.empty:
        area_unit = units.get("Cell_Area", "mm²")
        fmax_unit = units.get("Fmax", "MHz")
        power_unit = units.get("Total_Power", "mW")

        plot_combined_figure(
            df=df_results,
            area_unit=area_unit,
            fmax_unit=fmax_unit,
            power_unit=power_unit,
            output_dir=arch_dir,
            arch_folder=os.path.basename(os.path.normpath(arch_dir)),
            display_name=DISPLAY_NAME
        )
    else:
        print("Aucune donnée n'a pu être extraite des fichiers YAML.")