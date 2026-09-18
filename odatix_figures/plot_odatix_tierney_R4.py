import argparse
import yaml
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import os

# Se placer dans le dossier générique : cd /DDS
# Activer l'environnement virtuel : source venv/bin/activate
# Se placer dans le dossier parent : cd /trace_odatix
# Commande de compilation : python3 plot_odatix_tierney_R4.py


# ==========================================
# 1. PARAMETRES DU SCRIPT
# ==========================================
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ARCHITECTURE = 'R4_eight'  # R4_quarter  R4_eight

# Configuration visuelle globale
sns.set_theme(style="whitegrid")
plt.rcParams.update({'font.size': 12, 'axes.labelsize': 14})

# ==========================================
# 2. EXTRACTION ET PARSING DU YAML
# ==========================================
def charger_donnees(chemin_fichier):
    with open(chemin_fichier, 'r') as file:
        data = yaml.safe_load(file)
    
    unites = data.get('units', {})
    resultats_fmax = data.get('fmax_synthesis', {})
    
    lignes = []
    
    #Parcours de l'arborescence : Techno -> Architecture -> Config
    for techno, archs in resultats_fmax.items():
        for arch, config_dict in archs.items():
            for config_str, metrics in config_dict.items():
                if not metrics: 
                    continue
                
                # NOUVEAU FILTRE : On ignore toutes les anciennes configurations "lsb"
                # On ne garde que les chaines qui font exactement 4 caracteres de long (ex: "3335")
                config_clean = str(config_str).replace('Bits', '').replace('lsb', '')
                if len(config_clean) != 4:
                    continue
                
                cell_area_raw = metrics.get('Cell_Area')
                cell_area_mm2 = cell_area_raw / 1_000_000.0 if cell_area_raw is not None else None
                
                ligne = {
                    'Technologie': techno,
                    'Architecture': arch,
                    'Configuration': config_clean,
                    'Fmax': metrics.get('Fmax'),
                    'Cell_Area': cell_area_mm2,
                    'Internal_Power': metrics.get('Internal_Power'),
                    'Switching_Power': metrics.get('Switching_Power'),
                    'Leakage_Power': metrics.get('Leakage_Power'),
                    'Total_Power': metrics.get('Total_Power')
                }
                lignes.append(ligne)
                
    # Conversion en DataFrame Pandas et tri alphabetique des configurations
    df = pd.DataFrame(lignes)
    df = df.sort_values(by='Configuration')
    return df, unites

# ==========================================
# 3. FONCTION DE TRACE PARAMETRABLE
# ==========================================
def tracer_courbe(df, metrique_y, label_y, titre, output_dir, nom_arch):
    plt.figure(figsize=(10, 6))
    
    # Trace la courbe (Seaborn gere automatiquement l'axe X categoriel)
    sns.lineplot(
        data=df, 
        x='Configuration', 
        y=metrique_y, 
        hue='Technologie', 
        marker='o', 
        linewidth=2,
        markersize=8
    )
    
    # Nettoyage des legendes
    handles, labels = plt.gca().get_legend_handles_labels()
    labels_propres = [l.split('_')[-1] for l in labels] 
    plt.legend(handles, labels_propres, title="Tension (Vdd)")
    
    plt.title(titre, pad=15, fontweight='bold')
    plt.xlabel('Configuration des partitions ROM (W1, W2, W3, W4)')
    plt.ylabel(label_y)
    
    # ADAPTATION R4 : Rotation des etiquettes de l'axe X pour une lecture propre
    plt.xticks(rotation=45)
    
    plt.tight_layout()
    os.makedirs(output_dir, exist_ok=True)
    
    output_path = os.path.join(output_dir, f'{nom_arch}_{metrique_y}.pdf')
    
    plt.savefig(output_path, dpi=300, format='pdf')
    if 'agg' not in plt.get_backend().lower():
        plt.show()
    plt.close()

# ==========================================
# 4. EXECUTION PRINCIPALE
# ==========================================
def parse_args():
    parser = argparse.ArgumentParser(
        description='Trace et sauvegarde les resultats Odatix depuis un dossier d\'architecture.'
    )
    parser.add_argument(
        '-a', '--arch',
        default=DEFAULT_ARCHITECTURE,
        help='Nom du dossier d\'architecture (ex: R4_quarter).'
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    architecture = args.arch
    arch_dir = os.path.join(SCRIPT_DIR, architecture)
    FICHIER_YAML = os.path.join(arch_dir, 'results_design_compiler.yml')

    if not os.path.isdir(arch_dir):
        raise FileNotFoundError(f"Dossier d'architecture introuvable : {arch_dir}")
    if not os.path.isfile(FICHIER_YAML):
        raise FileNotFoundError(f"Fichier YAML introuvable : {FICHIER_YAML}")

    print(f"Chargement des donnees Odatix pour l'architecture : {architecture}")
    df_resultats, unites = charger_donnees(FICHIER_YAML)
    
    print("\nApercu des donnees extraites :")
    print(df_resultats.head())
    
    output_dir = arch_dir
    
    # --- Generation des graphiques ---
    
    unite_fmax = unites.get('Fmax', 'MHz')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Fmax', 
        label_y=f'Frequence Maximale Fmax ({unite_fmax})', 
        titre='Evolution du Fmax selon le partitionnement ROM',
        output_dir=output_dir,
        nom_arch=architecture
    )
    
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Cell_Area', 
        label_y='Surface logique (mm²)', 
        titre='Empreinte silicium selon le partitionnement ROM',
        output_dir=output_dir,
        nom_arch=architecture
    )
    
    unite_total = unites.get('Total_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Total_Power', 
        label_y=f'Puissance Totale ({unite_total})', 
        titre='Consommation energetique totale selon le partitionnement',
        output_dir=output_dir,
        nom_arch=architecture
    )

    unite_internal = unites.get('Internal_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Internal_Power', 
        label_y=f'Puissance Interne ({unite_internal})', 
        titre='Puissance interne (dissipee dans les cellules)',
        output_dir=output_dir,
        nom_arch=architecture
    )

    unite_switching = unites.get('Switching_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Switching_Power', 
        label_y=f'Puissance de Commutation ({unite_switching})', 
        titre='Puissance de commutation (dissipee dans les interconnexions)',
        output_dir=output_dir,
        nom_arch=architecture
    )

    unite_leakage = unites.get('Leakage_Power', 'pW') 
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Leakage_Power', 
        label_y=f'Puissance de Fuite ({unite_leakage})', 
        titre='Puissance de fuite statique selon le partitionnement',
        output_dir=output_dir,
        nom_arch=architecture
    )