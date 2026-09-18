import argparse
import yaml
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import os

# Navigate to the generic folder: cd /DDS
# Activate the virtual environment: source venv/bin/activate
# Navigate to the parent folder: cd /trace_odatix
# Execution command: python3 publi_Madisetti.py

# ==========================================
# 1. SCRIPT PARAMETERS
# ==========================================
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# Default folder name for the new architecture files
DEFAULT_ARCHITECTURE = 'Publi_Madisetti'  

# FORCE YOUR OWN DISPLAY NAME HERE FOR THE PUBLICATION TITLE
DISPLAY_NAME = "Madisetti"  

# Global visual configuration
sns.set_theme(style="whitegrid")
plt.rcParams.update({'font.size': 12, 'axes.labelsize': 14})

# ==========================================
# 2. YAML EXTRACTION AND PARSING
# ==========================================
def load_data(file_path):
    with open(file_path, 'r') as file:
        data = yaml.safe_load(file)
    
    units = data.get('units', {})
    fmax_results = data.get('fmax_synthesis', {})
    
    rows = []
    
    # MODIFICATION : Allowed configurations updated for Madisetti letter variants
    allowed_configs = {'A', 'B', 'C', 'D', 'E', 'F', 'G'}
    
    for techno, archs in fmax_results.items():
        for arch, bits_dict in archs.items():
            for bit_str, metrics in bits_dict.items():
                if not metrics: 
                    continue 
                
                # Strict filtering on the architecture design cases
                if str(bit_str) not in allowed_configs:
                    continue
                
                row = {
                    'Technology': techno,
                    'Architecture': arch,
                    'Resolution_Bits': str(bit_str), 
                    'Fmax': metrics.get('Fmax'),
                    'Cell_Area': metrics.get('Cell_Area'),
                    'Total_Power': metrics.get('Total_Power'),
                }
                rows.append(row)
                
    df = pd.DataFrame(rows)
    
    # MODIFICATION : Categorical sorting matching your sequential design matrix (A to G)
    custom_order = ['A', 'B', 'C', 'D', 'E', 'F', 'G']
    df['Resolution_Bits'] = pd.Categorical(df['Resolution_Bits'], categories=custom_order, ordered=True)
    df = df.sort_values(by='Resolution_Bits')

    if 'Cell_Area' in df.columns:
        df['Cell_Area'] = df['Cell_Area'] / 1_000_000.0
    
    units['Cell_Area'] = 'mm²'

    return df, units

# ==========================================
# 3. COMBINED PLOTTING FUNCTION
# ==========================================
def plot_combined_figure(df, area_unit, fmax_unit, power_unit, output_dir, arch_name, display_name):
    df_0v9 = df[df['Technology'] == 'TSMC_N28_HPCplus_P140_12T_0V9'].copy()
    
    if df_0v9.empty:
        print("No data found for the 0.9V technology.")
        return

    fig, (ax1, ax3) = plt.subplots(2, 1, figsize=(10, 10), sharex=True)
    
    # --- TOP SUBPLOT: Area (Left) and Total Power (Right) ---
    ax1.set_title("(a)", loc='left', fontweight='bold', fontsize=16)
    
    color1 = 'tab:blue'
    ax1.set_ylabel(f'Logic Area ({area_unit})', color=color1, fontweight='bold')
    line1 = ax1.plot(df_0v9['Resolution_Bits'], df_0v9['Cell_Area'], color=color1, marker='o', linewidth=2, label='Area (mm²)')
    ax1.tick_params(axis='y', labelcolor=color1)
    
    ax2 = ax1.twinx()
    color3 = 'tab:green'
    ax2.set_ylabel(f'Total Power ({power_unit})', color=color3, fontweight='bold')
    ax2.grid(False) 
    line2 = ax2.plot(df_0v9['Resolution_Bits'], df_0v9['Total_Power'], color=color3, marker='^', linewidth=2, label='Total Power')
    ax2.tick_params(axis='y', labelcolor=color3)

    # --- BOTTOM SUBPLOT: Fmax ---
    ax3.set_title("(b)", loc='left', fontweight='bold', fontsize=16)
    
    color2 = 'tab:red'
    ax3.set_xlabel('Configuration / Phase Width', fontweight='bold')
    ax3.set_ylabel(f'Maximum Frequency Fmax ({fmax_unit})', color=color2, fontweight='bold')
    line3 = ax3.plot(df_0v9['Resolution_Bits'], df_0v9['Fmax'], color=color2, marker='s', linewidth=2, label='Fmax (MHz)')
    ax3.tick_params(axis='y', labelcolor=color2)
    
    ax3.set_xticks(range(len(df_0v9['Resolution_Bits'])))
    ax3.set_xticklabels(df_0v9['Resolution_Bits'])
    ax3.tick_params(axis='x', rotation=0) 
    
    # --- GLOBAL TITLE AND UNIFIED LEGEND ---
    lines = line1 + line2 + line3
    labels = [l.get_label() for l in lines]
    
    fig.suptitle(f'Architecture: {display_name}', fontweight='bold', y=0.97)
    
    fig.legend(lines, labels, loc='upper center', bbox_to_anchor=(0.5, 0.93), ncol=3, frameon=True)
    
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    
    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, f'{arch_name}_Combined_Metrics_0V9.pdf')
    
    plt.savefig(output_path, dpi=300, format='pdf', bbox_inches='tight')
    if 'agg' not in plt.get_backend().lower():
        plt.show()
    plt.close()

# ==========================================
# 4. MAIN EXECUTION
# ==========================================
def parse_args():
    parser = argparse.ArgumentParser(
        description='Plots and saves combined Odatix results (Area/Power and Fmax) for 0.9V.'
    )
    parser.add_argument(
        '-a', '--arch',
        default=DEFAULT_ARCHITECTURE,
        help='Name of the architecture folder.'
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    architecture = args.arch
    arch_dir = os.path.join(SCRIPT_DIR, architecture)
    YAML_FILE = os.path.join(arch_dir, 'results_design_compiler.yml')

    if not os.path.isdir(arch_dir):
        print(f"Warning: The folder {arch_dir} was not found.")
    elif not os.path.isfile(YAML_FILE):
        print(f"Warning: The YAML file {YAML_FILE} was not found.")
    else:
        print(f"Loading Odatix data for the architecture: {architecture}")
        df_results, units = load_data(YAML_FILE)
        
        output_dir = arch_dir
        
        area_unit = units.get('Cell_Area', 'mm²')
        fmax_unit = units.get('Fmax', 'MHz')
        power_unit = units.get('Total_Power', 'mW')
        
        plot_combined_figure(
            df=df_results, 
            area_unit=area_unit, 
            fmax_unit=fmax_unit, 
            power_unit=power_unit,
            output_dir=output_dir,
            arch_name=architecture,
            display_name=DISPLAY_NAME
        )