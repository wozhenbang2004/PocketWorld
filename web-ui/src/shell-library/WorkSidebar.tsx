/* PW-16: actual BoardUI DashboardSidebar MIT source adaptation.
 * Original file is retained in upstream/boardui. Keep its pinned icon/label and NavItem
 * structure; remove sample team/profile/support, fake counts and theme controls.
 */
import type {ComponentType,ReactNode} from 'react';
import {useTemplateCopy} from './adapted/boardui/components/foundations/template-copy/template-copy';
import {cx} from './adapted/boardui/utils/cx';
type IconComponent=ComponentType<{className?:string;'aria-hidden'?:boolean|'true'|'false'}>;
function Collapsible({ collapsed, children, className }: { collapsed: boolean; children: ReactNode; className?: string }) {
  const localize = useTemplateCopy();
  return localize((
    <span
      className={cx(
        "flex min-w-0 items-center overflow-hidden transition-[max-width,opacity,filter] duration-300 ease-in-out",
        // Expanded, the cap is the row itself: a fixed cap (it was 160px)
        // clipped any label wider than it, "Components and Blocks" included.
        collapsed ? "max-w-0 opacity-0 blur-[3px]" : "max-w-full opacity-100 blur-0",
        className,
      )}
    >
      {children}
    </span>
  ));
}

function NavItem({
  icon: Icon,
  label,
  badge,
  isSelected = false,
  collapsed = false,
  href = "#",
  onClick,
}: {
  icon: IconComponent;
  label: string;
  badge?: ReactNode;
  isSelected?: boolean;
  collapsed?: boolean;
  href?: string;
  /** Action rows (e.g. Settings → modal) intercept the navigation. */
  onClick?: () => void;
}) {
  const localize = useTemplateCopy();
  return localize((
    <a
      href={href}
      onClick={
        onClick
          ? (event) => {
              event.preventDefault();
              onClick();
            }
          : undefined
      }
      aria-current={isSelected ? "page" : undefined}
      aria-label={label}
      title={collapsed ? label : undefined}
      className={cx(
        "flex items-center justify-between overflow-hidden rounded-2lg p-2",
        "transition-[width,background-color] duration-300 ease-in-out",
        collapsed ? "w-9" : "w-full",
        isSelected
          ? "bg-neutral-900 shadow-nav-selected"
          : "hover:bg-background-secondary-hover",
      )}
    >
      <span className="flex min-w-0 items-center gap-2">
        <Icon
          className={cx("size-5 shrink-0", isSelected ? "text-white" : "text-foreground-icon-secondary")}
          aria-hidden
        />
        <Collapsible collapsed={collapsed}>
          <span
            className={cx(
              "text-body-medium whitespace-nowrap",
              isSelected ? "text-white" : "text-text-secondary",
            )}
          >
            {label}
          </span>
        </Collapsible>
      </span>
      {badge && <Collapsible collapsed={collapsed}>{badge}</Collapsible>}
    </a>
  ));
}

/** A primary navigation row. Rows without an `href` are decoration only. */

export type WorkNavItem={key:string;label:string;icon:IconComponent;count?:number;group?:string;description?:string;disabled?:boolean};
export function WorkSidebar({items,selected,onNavigate,header,footer}:{items:WorkNavItem[];selected:string;onNavigate:(key:string)=>void;header?:ReactNode;footer?:ReactNode}) {
 const groups=Array.from(new Set(items.map(x=>x.group||'工作')));
 return <aside className="pw-work-sidebar" aria-label="工作与功能">{header}<nav>{groups.map(group=><section key={group}><h3>{group}</h3>{items.filter(x=>(x.group||'工作')===group).map(item=><div className="pw-nav-item" key={item.key}><NavItem icon={item.icon} label={item.label} isSelected={selected===item.key} badge={item.count!=null?<span className="pw-nav-count">{item.count}</span>:undefined} onClick={()=>{if(!item.disabled)onNavigate(item.key)}}/>{item.description&&<small>{item.description}</small>}</div>)}</section>)}</nav>{footer}</aside>
}
