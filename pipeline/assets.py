"""What GTA WI takes from a GTA III (PC) install. Shared by extract.py, render_sprites.py and export.py."""

# peds rendered standing / walking (walk_civi, 4 frames) / dead (KO_shot_front): name -> GTA model (dff + txd)
PEDS_WALK = {
    'claude': 'playerp', 'eight': 'eight', 'misty': 'misty', 'cop': 'cop',      # playerp = Claude in prison clothes
    'gang01': 'gang01', 'gang03': 'gang03', 'ct_man1': 'ct_man1', 'ct_man2': 'ct_man2', 'ct_wom1': 'ct_wom1',
    'ct_wom2': 'ct_wom2', 'li_man1': 'li_man1', 'p_man1': 'p_man1', 'p_wom1': 'p_wom1', 'scum_man': 'scum_man',
    'docker1': 'docker1',
}
# run cycles (8 frames per direction) + stand/dead for the tiers that have no walk render
PEDS_RUN = {'claude': ('playerp', 'run_player'), 'cop': ('cop', 'run_civi'), 'swat': ('swat', 'run_civi'),
            'fbi': ('fbi', 'run_civi'), 'army': ('army', 'run_civi')}
RUN_EXTRA = ('swat', 'fbi', 'army')            # these also get stand + dead from the run pass
# Claude's attacks: (anim, frames, weapon object in Generic\weapons.dff or None)
ATTACKS = {'punch': ('FIGHTppunch', 4, None), 'gun': ('WEAPON_hgun_body', 3, 'colt45')}
# civilians the game picks from (the rest of PEDS_WALK are special or cops)
CIVILIANS = ['gang01', 'ct_man1', 'ct_man2', 'ct_wom1', 'ct_wom2', 'li_man1', 'p_man1', 'p_wom1', 'scum_man', 'docker1']
# cars: 16 directions. The first 12 are traffic + the Kuruma, then the cop cars of the waves
CARS = ['kuruma', 'police', 'stallion', 'idaho', 'manana', 'taxi', 'mafia', 'peren', 'esperant', 'blista',
        'sentinel', 'cabbie', 'enforcer', 'fbicar', 'barracks']
CAR_COMBO = {'kuruma': 2}                       # carcols.dat colour pair (default: the first); 8-Ball's Kuruma is the teal one
HELI = 'chopper'
PORTRAITS = {'claude': 'playerp', 'eight': 'eight'}

# loose files (relative to the GTA III folder, any case) -> work/gta3/<same path, lower case>
LOOSE = ['models/hud.txd', 'models/fonts.txd', 'models/menu.txd', 'models/misc.txd', 'models/generic.txd',
         'models/generic/wheels.dff', 'models/generic/weapons.dff', 'txd/news.txd', 'anim/ped.ifp',
         'data/default.ide', 'data/default.dat', 'data/gta3.dat', 'data/carcols.dat', 'text/american.gxt']
LOOSE_DIRS = ['data/maps']                      # IPL/IDE files for the road + pavement graphs
