import argparse
import yaml
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import os

# Se placer dans le dossier generique : cd /DDS
# Activer l'environnement virtuel : source venv/bin/activate
# Se placer dans le dossier parent : cd /trace_odatix
# Commande de compilation : python3 plot_odatix_madisetti.py



# ==========================================
# 1. PARAMETRES DU SCRIPT
# ==========================================
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ARCHITECTURE = 'Madisetti'  

# Configuration visuelle globale (style Seaborn pour des graphiques propres)
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
    
    # Parcours de l'arborescence : Techno -> Architecture -> Madisetti_X_Y
    for techno, archs in resultats_fmax.items():
        for arch, config_dict in archs.items():
            for config_str, metrics in config_dict.items():
                if not metrics: 
                    continue
                
                # ADAPTATION : Extraction de la largeur du bus interne (Y) depuis la clé 'Madisetti_X_Y'
                # Exemple : config_str = "Madisetti_36_22" -> split('_') donne ['Madisetti', '36', '22']
                parts = config_str.split('_')
                if len(parts) >= 3:
                    bit_val = int(parts[2])  # On extrait le 22
                else:
                    bit_val = 0 # Fallback au cas où le nommage serait différent
                
                # Création d'une ligne de tableau avec TOUTES les puissances
                ligne = {
                    'Technologie': techno,
                    'Architecture': arch,
                    'Resolution_Bits': bit_val,
                    'Fmax': metrics.get('Fmax'),
                    'Cell_Area': metrics.get('Cell_Area'),
                    'Internal_Power': metrics.get('Internal_Power'),
                    'Switching_Power': metrics.get('Switching_Power'),
                    'Leakage_Power': metrics.get('Leakage_Power'),
                    'Total_Power': metrics.get('Total_Power')
                }
                lignes.append(ligne)
                
    # Conversion en DataFrame Pandas et tri par resolution
    df = pd.DataFrame(lignes)
    df = df.sort_values(by='Resolution_Bits')

    # Conversion de l'aire de µm² à mm²
    if 'Cell_Area' in df.columns:
        df['Cell_Area'] = df['Cell_Area'] / 1_000_000.0
    
    # Remplacement de l'unité pour l'affichage graphique
    unites['Cell_Area'] = 'mm²'

    return df, unites

# ==========================================
# 3. FONCTION DE TRACE PARAMETRABLE
# ==========================================
def tracer_courbe(df, metrique_y, label_y, titre, output_dir, nom_arch):
    # Sécurité : Si le DataFrame est vide ou ne contient qu'une valeur sur l'axe X, 
    # le tracé en courbe n'est pas pertinent, on trace un graphique en barres.
    unique_x = df['Resolution_Bits'].nunique()
    
    plt.figure(figsize=(10, 6))
    
    if unique_x > 1:
        # Trace une courbe pour chaque technologie
        sns.lineplot(
            data=df, 
            x='Resolution_Bits', 
            y=metrique_y, 
            hue='Technologie', 
            marker='o', 
            linewidth=2,
            markersize=8
        )
    else:
        # Trace un diagramme en barres s'il n'y a qu'un seul point de résolution
        sns.barplot(
            data=df,
            x='Technologie',
            y=metrique_y,
            hue='Technologie'
        )
        # Ajustement des labels X pour le barplot
        plt.xticks(rotation=15, ha='right')
        titre = titre + f" (Bus interne: {df['Resolution_Bits'].iloc[0]} bits)"
    
    # Nettoyage des legendes (pour ne garder que la fin du nom de la techno) si c'est un lineplot
    if unique_x > 1:
        handles, labels = plt.gca().get_legend_handles_labels()
        labels_propres = [l.split('_')[-1] for l in labels] # Ex: garde juste "0V8"
        plt.legend(handles, labels_propres, title="Tension (Vdd)")
        plt.xlabel('Largeur du bus de phase (Bits)')
        plt.xticks(df['Resolution_Bits'].unique())
    else:
        plt.xlabel('Technologie (Vdd)')
        plt.legend([],[], frameon=False) # Pas de légende nécessaire pour le barplot

    plt.title(titre, pad=15, fontweight='bold')
    plt.ylabel(label_y)
    
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
        description='Trace et sauvegarde les résultats Odatix depuis un dossier d\'architecture.'
    )
    parser.add_argument(
        '-a', '--arch',
        default=DEFAULT_ARCHITECTURE,
        help='Nom du dossier d\'architecture (ex: Madisetti).'
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
    
    # 1. Trace de la Frequence Max (Fmax)
    unite_fmax = unites.get('Fmax', 'MHz')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Fmax', 
        label_y=f'Frequence Maximale Fmax ({unite_fmax})', 
        titre='Evolution du Fmax',
        output_dir=output_dir,
        nom_arch=architecture
    )
    
    # 2. Trace de l'Occupation Silicium (Cell Area)
    unite_area = unites.get('Cell_Area', 'um^2')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Cell_Area', 
        label_y=f'Surface logique ({unite_area})', 
        titre='Empreinte silicium',
        output_dir=output_dir,
        nom_arch=architecture
    )
    
    # 3. Trace de la Consommation Totale (Total Power)
    unite_total = unites.get('Total_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Total_Power', 
        label_y=f'Puissance Totale ({unite_total})', 
        titre='Consommation energetique totale',
        output_dir=output_dir,
        nom_arch=architecture
    )

    # 4. Trace de la Consommation Interne (Internal Power)
    unite_internal = unites.get('Internal_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Internal_Power', 
        label_y=f'Puissance Interne ({unite_internal})', 
        titre='Puissance interne (dissipee dans les cellules)',
        output_dir=output_dir,
        nom_arch=architecture
    )

    # 5. Trace de la Puissance de Commutation (Switching Power)
    unite_switching = unites.get('Switching_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Switching_Power', 
        label_y=f'Puissance de Commutation ({unite_switching})', 
        titre='Puissance de commutation (interconnexions)',
        output_dir=output_dir,
        nom_arch=architecture
    )

    # 6. Trace des Courants de Fuite (Leakage Power)
    unite_leakage = unites.get('Leakage_Power', 'pW') 
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Leakage_Power', 
        label_y=f'Puissance de Fuite ({unite_leakage})', 
        titre='Puissance de fuite statique',
        output_dir=output_dir,
        nom_arch=architecture
    )