import glob
import os
import re
import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns
import yaml

# ==========================================
# 1. CONFIGURATION : DOSSIER À TRAITER
# ==========================================
# Renseigne ici le nom exact de ton dossier (ex: 'Taylor2_14bits_V2', 'Publi_Taylor2_V3', etc.)
DOSSIER_ARCHI = "Publi_Taylor2_V3"

# Configuration graphique
sns.set_theme(style="whitegrid")
plt.rcParams.update({"font.size": 12, "axes.labelsize": 14})

# ==========================================
# 2. EXTRACTION ET PARSING DES FICHIERS YAML
# ==========================================
def charger_donnees(chemins_fichiers):
    unites_globales = {}
    lignes = []

    for chemin in chemins_fichiers:
        with open(chemin, "r") as file:
            data = yaml.safe_load(file)

        if not data:
            continue

        unites = data.get("units", {})
        unites_globales.update(unites)
        resultats_fmax = data.get("fmax_synthesis", {})

        for techno, accu_dict in resultats_fmax.items():
            for acc_str, segments_dict in accu_dict.items():

                # Extraction du nombre de bits pour l'affichage (ex: '14 Bits')
                match_bits = re.search(r"(\d+)\s*bits?", acc_str, re.IGNORECASE)
                if match_bits:
                    acc_label = f"{match_bits.group(1)} Bits"
                    nb_bits = int(match_bits.group(1))
                else:
                    acc_label = acc_str
                    nb_bits = 0

                for seg_str, metrics in segments_dict.items():
                    if not metrics:
                        continue

                    # Conversion de '2seg', '4seg', etc. en entier
                    match_seg = re.search(r"(\d+)", seg_str)
                    seg_val = int(match_seg.group(1)) if match_seg else int(seg_str.replace("seg", ""))

                    surface_um2 = metrics.get("Cell_Area")
                    surface_mm2 = surface_um2 / 1_000_000.0 if surface_um2 is not None else None

                    lignes.append({
                        "Technologie": techno,
                        "Architecture": acc_str,
                        "Accumulateur": acc_label,
                        "Bits": nb_bits,
                        "Segments": seg_val,
                        "Fmax": metrics.get("Fmax"),
                        "Cell_Area": surface_mm2,
                        "Internal_Power": metrics.get("Internal_Power"),
                        "Switching_Power": metrics.get("Switching_Power"),
                        "Leakage_Power": metrics.get("Leakage_Power"),
                        "Total_Power": metrics.get("Total_Power")
                    })

    df = pd.DataFrame(lignes)
    if not df.empty:
        df = df.sort_values(by=["Bits", "Segments"])
    return df, unites_globales

# ==========================================
# 3. TRACÉ DES GRAPHIQUES
# ==========================================
def tracer_courbe(df, metrique_y, label_y, titre, output_dir, prefixe):
    plt.figure(figsize=(10, 6))

    sns.lineplot(
        data=df,
        x="Segments",
        y=metrique_y,
        hue="Accumulateur",
        marker="o",
        linewidth=2,
        markersize=8,
        palette="Set1"
    )

    plt.title(titre, pad=15, fontweight="bold")
    plt.xlabel("Nombre de segments (Partitionnement ROM)")
    plt.ylabel(label_y)

    valeurs_segments = sorted(df["Segments"].unique())
    plt.xticks(valeurs_segments)

    plt.tight_layout()
    output_path = os.path.join(output_dir, f"{prefixe}_{metrique_y}.pdf")
    plt.savefig(output_path, dpi=300, format="pdf")
    if "agg" not in plt.get_backend().lower():
        plt.show()
    plt.close()

# ==========================================
# 4. EXÉCUTION
# ==========================================
if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    
    # Résolution de l'emplacement du dossier cible
    if os.path.isabs(DOSSIER_ARCHI):
        dossier_cible = DOSSIER_ARCHI
    else:
        dossier_cible = os.path.join(script_dir, DOSSIER_ARCHI)
        if not os.path.isdir(dossier_cible):
            dossier_cible = os.path.abspath(DOSSIER_ARCHI)

    if not os.path.isdir(dossier_cible):
        raise FileNotFoundError(f"Dossier introuvable : {dossier_cible}")

    # Recherche automatique des fichiers YAML dans le dossier cible
    fichiers_yaml = sorted(
        glob.glob(os.path.join(dossier_cible, "*.yml")) +
        glob.glob(os.path.join(dossier_cible, "*.yaml"))
    )

    if not fichiers_yaml:
        # Recherche dans les sous-dossiers éventuels
        fichiers_yaml = sorted(
            glob.glob(os.path.join(dossier_cible, "**", "*.yml"), recursive=True) +
            glob.glob(os.path.join(dossier_cible, "**", "*.yaml"), recursive=True)
        )

    if not fichiers_yaml:
        raise FileNotFoundError(f"Aucun fichier .yml trouvé dans {dossier_cible}")

    print(f"Dossier de travail : {dossier_cible}")
    print(f"Fichiers trouves ({len(fichiers_yaml)}) :")
    for f in fichiers_yaml:
        print(f"  - {os.path.basename(f)}")

    df_resultats, unites = charger_donnees(fichiers_yaml)

    if df_resultats.empty:
        raise ValueError("Aucune donnee exploitable trouvee dans les YAML.")

    prefixe = os.path.basename(os.path.normpath(dossier_cible))

    # Génération des 6 PDF directement dans le dossier cible
    tracer_courbe(
        df=df_resultats,
        metrique_y="Fmax",
        label_y=f"Frequence Maximale Fmax ({unites.get('Fmax', 'MHz')})",
        titre="Evolution du Fmax en fonction du nombre de segments",
        output_dir=dossier_cible,
        prefixe=prefixe
    )

    tracer_courbe(
        df=df_resultats,
        metrique_y="Cell_Area",
        label_y="Surface logique (mm²)",
        titre="Empreinte silicium en fonction du partitionnement",
        output_dir=dossier_cible,
        prefixe=prefixe
    )

    tracer_courbe(
        df=df_resultats,
        metrique_y="Total_Power",
        label_y=f"Puissance Totale ({unites.get('Total_Power', 'mW')})",
        titre="Consommation energetique totale",
        output_dir=dossier_cible,
        prefixe=prefixe
    )

    tracer_courbe(
        df=df_resultats,
        metrique_y="Internal_Power",
        label_y=f"Puissance Interne ({unites.get('Internal_Power', 'mW')})",
        titre="Puissance interne (dissipee dans les cellules)",
        output_dir=dossier_cible,
        prefixe=prefixe
    )

    tracer_courbe(
        df=df_resultats,
        metrique_y="Switching_Power",
        label_y=f"Puissance de Commutation ({unites.get('Switching_Power', 'mW')})",
        titre="Puissance de commutation (interconnexions)",
        output_dir=dossier_cible,
        prefixe=prefixe
    )

    tracer_courbe(
        df=df_resultats,
        metrique_y="Leakage_Power",
        label_y=f"Puissance de Fuite ({unites.get('Leakage_Power', 'pW')})",
        titre="Puissance de fuite statique",
        output_dir=dossier_cible,
        prefixe=prefixe
    )

    print(f"\nPDF generes dans : {dossier_cible}")