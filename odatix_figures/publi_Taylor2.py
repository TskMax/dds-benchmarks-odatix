import glob
import os
import re
import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns
import yaml

# ==========================================
# 1. PARAMÈTRES DU SCRIPT
# ==========================================
# Modifie simplement ce nom selon le dossier à traiter
DOSSIER_ARCHI = "Publi_Taylor2_V3"

DISPLAY_NAME = "Taylor Series (2nd Order) - 14-bit vs 18-bit"

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
                for seg_str, metrics in segs_dict.items():
                    if not metrics:
                        continue

                    # Conversion : '2seg', '16seg', '16segments' -> 16
                    match_seg = re.search(r"(\d+)", str(seg_str))
                    seg_val = int(match_seg.group(1)) if match_seg else int(seg_str)

                    rows.append({
                        "Technology": techno,
                        "Architecture": arch,
                        "Segments": seg_val,
                        "Fmax": metrics.get("Fmax"),
                        "Cell_Area": metrics.get("Cell_Area"),
                        "Total_Power": metrics.get("Total_Power"),
                    })

    df = pd.DataFrame(rows)

    if not df.empty:
        if "Cell_Area" in df.columns:
            df["Cell_Area"] = df["Cell_Area"] / 1_000_000.0  # um² -> mm²
        df = df.sort_values(by=["Architecture", "Segments"])

    global_units["Cell_Area"] = "mm²"
    return df, global_units

# ==========================================
# 3. TRACÉ COMBINÉ
# ==========================================
def plot_combined_figure(df, area_unit, fmax_unit, power_unit, output_dir, arch_folder, display_name):
    target_techno = "TSMC_N28_HPCplus_P140_12T_0V9"
    df_0v9 = df[df["Technology"] == target_techno].copy()

    if df_0v9.empty:
        print(f"Aucune donnee trouvee pour la technologie : {target_techno}")
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

    # Détection dynamique des configurations (gère Taylor2_14bits_V2 et Taylor_14bits_publi)
    architectures = df_0v9["Architecture"].unique()
    arch_configs = {}
    for arch_name in architectures:
        if "14" in arch_name:
            arch_configs[arch_name] = {"label": "14-bit", "marker": "o", "ls": "-"}
        elif "18" in arch_name:
            arch_configs[arch_name] = {"label": "18-bit", "marker": "s", "ls": "--"}
        else:
            arch_configs[arch_name] = {"label": arch_name, "marker": "^", "ls": ":"}

    lines = []

    for arch_key, cfg in arch_configs.items():
        subset = df_0v9[df_0v9["Architecture"] == arch_key]
        if subset.empty:
            continue

        lbl = cfg["label"]

        # Area (Bleu)
        l1, = ax1.plot(subset["Segments"], subset["Cell_Area"], color="tab:blue",
                       marker=cfg["marker"], linestyle=cfg["ls"], linewidth=2,
                       label=f"Area ({lbl})")
        # Power (Vert)
        l2, = ax2.plot(subset["Segments"], subset["Total_Power"], color="tab:green",
                       marker=cfg["marker"], linestyle=cfg["ls"], linewidth=2,
                       label=f"Power ({lbl})")
        # Fmax (Rouge)
        l3, = ax3.plot(subset["Segments"], subset["Fmax"], color="tab:red",
                       marker=cfg["marker"], linestyle=cfg["ls"], linewidth=2,
                       label=f"Fmax ({lbl})")

        lines.extend([l1, l2, l3])

    ax1.tick_params(axis="y", labelcolor="tab:blue")
    ax2.tick_params(axis="y", labelcolor="tab:green")
    ax3.tick_params(axis="y", labelcolor="tab:red")

    unique_segs = sorted(df_0v9["Segments"].unique())
    ax3.set_xticks(unique_segs)
    ax3.set_xticklabels([str(s) for s in unique_segs])

    labels = [l.get_label() for l in lines]
    fig.suptitle(f"Architecture: {display_name}", fontweight="bold", y=0.98)
    fig.legend(lines, labels, loc="upper center", bbox_to_anchor=(0.5, 0.94), ncol=3, frameon=True)

    fig.tight_layout(rect=[0, 0, 1, 0.88])

    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, f"{arch_folder}_Combined_Metrics_0V9.pdf")

    plt.savefig(output_path, dpi=300, format="pdf", bbox_inches="tight")
    print(f"Graphique sauvegarde dans : {output_path}")

    if "agg" not in plt.get_backend().lower():
        plt.show()
    plt.close()

# ==========================================
# 4. EXÉCUTION PRINCIPALE
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

    # Recherche des YAML (priorite aux fichiers nommes 14bits/18bits, puis tous les .yml)
    yaml_files = sorted(glob.glob(os.path.join(arch_dir, "*14*.yml")) + glob.glob(os.path.join(arch_dir, "*18*.yml")))
    if not yaml_files:
        yaml_files = sorted(glob.glob(os.path.join(arch_dir, "*.yml")) + glob.glob(os.path.join(arch_dir, "*.yaml")))

    if not yaml_files:
        raise FileNotFoundError(f"Aucun fichier YAML trouve dans : {arch_dir}")

    print(f"Chargement des donnees depuis : {arch_dir}")
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
        print("Aucune donnee n'a pu etre extraite des fichiers YAML.")